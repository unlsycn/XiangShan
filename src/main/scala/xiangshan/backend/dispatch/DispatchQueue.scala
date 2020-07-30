package xiangshan.backend.dispatch

import chisel3._
import chisel3.util._
import utils.{XSDebug, XSError, XSInfo}
import xiangshan.{MicroOp, Redirect, RoqCommit, XSBundle, XSModule}


class DispatchQueueIO(enqnum: Int, deqnum: Int) extends XSBundle {
  val enq = Vec(enqnum, Flipped(DecoupledIO(new MicroOp)))
  val deq = Vec(deqnum, DecoupledIO(new MicroOp))
  val commits = Input(Vec(CommitWidth, Valid(new RoqCommit)))
  val redirect = Flipped(ValidIO(new Redirect))

  override def cloneType: DispatchQueueIO.this.type =
    new DispatchQueueIO(enqnum, deqnum).asInstanceOf[this.type]
}

// dispatch queue: accepts at most enqnum uops from dispatch1 and dispatches deqnum uops at every clock cycle
class DispatchQueue(size: Int, enqnum: Int, deqnum: Int, dpqType: Int) extends XSModule {
  val io = IO(new DispatchQueueIO(enqnum, deqnum))
  val indexWidth = log2Ceil(size)

  val s_invalid :: s_valid :: s_dispatched :: Nil = Enum(3)

  // queue data array
  val uopEntries = Reg(Vec(size, new MicroOp))
  val stateEntries = RegInit(VecInit(Seq.fill(size)(s_invalid)))
  // head: first valid entry (dispatched entry)
  val headPtr = RegInit(0.U((indexWidth + 1).W))
  val headIndex = headPtr(indexWidth - 1, 0)
  val headDirection = headPtr(indexWidth)
  // dispatch: first entry that has not been dispatched
  val dispatchPtr = RegInit(0.U((indexWidth + 1).W))
  val dispatchIndex = dispatchPtr(indexWidth - 1, 0)
  val dispatchDirection = dispatchPtr(indexWidth)
  // tail: first invalid entry (free entry)
  val tailPtr = RegInit(0.U((indexWidth + 1).W))
  val tailIndex = tailPtr(indexWidth - 1, 0)
  val tailDirection = tailPtr(indexWidth)

  // commit: starting from head ptr
  val commitPtr = (0 until CommitWidth).map(i => headPtr + i.U)
  val commitIndex = commitPtr.map(ptr => ptr(indexWidth - 1, 0))
  // deq: starting from dispatch ptr
  val deqPtr = (0 until enqnum).map(i => dispatchPtr + i.U)
  val deqIndex = deqPtr.map(ptr => ptr(indexWidth - 1, 0))
  // enq: starting from tail ptr
  val enqPtr = (0 until enqnum).map(i => tailPtr + i.U)
  val enqIndex = enqPtr.map(ptr => ptr(indexWidth - 1, 0))

  def greaterOrEqualThan(left: UInt, right: UInt) = {
    Mux(
      left(indexWidth) === right(indexWidth),
      left(indexWidth - 1, 0) >= right(indexWidth - 1, 0),
      left(indexWidth - 1, 0) <= right(indexWidth - 1, 0)
    )
  }
  XSError(!greaterOrEqualThan(tailPtr, headPtr), p"assert greaterOrEqualThan(tailPtr: $tailPtr, headPtr: $headPtr) failed\n")
  XSError(!greaterOrEqualThan(tailPtr, dispatchPtr), p"assert greaterOrEqualThan(tailPtr: $tailPtr, dispatchPtr: $dispatchPtr) failed\n")
  XSError(!greaterOrEqualThan(dispatchPtr, headPtr), p"assert greaterOrEqualThan(dispatchPtr: $dispatchPtr, headPtr: $headPtr) failed\n")

  val validEntries = Mux(headDirection === tailDirection, tailIndex - headIndex, size.U + tailIndex - headIndex)
  val dispatchEntries = Mux(dispatchDirection === tailDirection, tailIndex - dispatchIndex, size.U + tailIndex - dispatchIndex)
  val emptyEntries = size.U - validEntries

  // check whether valid uops are canceled
  val roqNeedFlush = Wire(Vec(size, Bool()))
  for (i <- 0 until size) {
    roqNeedFlush(i) := uopEntries(i).needFlush(io.redirect)
  }

  // cancelled uops should be set to invalid from enqueue input
  // we don't need to compare their brTags here
  for (i <- 0 until enqnum) {
    when (io.enq(i).fire()) {
      uopEntries(enqIndex(i)) := io.enq(i).bits
      stateEntries(enqIndex(i)) := s_valid
    }
  }

  for (i <- 0 until deqnum) {
    when (io.deq(i).fire()) {
      stateEntries(deqIndex(i)) := s_dispatched
    }
  }

  // cancel uops currently in the queue
  for (i <- 0 until size) {
    val needCancel = stateEntries(i) === s_valid && ((roqNeedFlush(i) && io.redirect.bits.isMisPred) || io.redirect.bits.isException)
    when (needCancel) {
      stateEntries(i) := s_invalid
    }

    XSInfo(needCancel, p"$name: valid entry($i)(pc = ${Hexadecimal(uopEntries(i).cf.pc)}) " +
      p"cancelled with brTag ${Hexadecimal(io.redirect.bits.brTag.value)}\n")
  }

  // enqueue
  val numEnqTry = Mux(emptyEntries > enqnum.U, enqnum.U, emptyEntries)
  val enqReadyBits = (1.U << numEnqTry).asUInt() - 1.U
  (0 until enqnum).map(i => io.enq(i).ready := enqReadyBits(i).asBool())
  val numEnq = PriorityEncoder(io.enq.map(!_.fire()) :+ true.B)
  tailPtr := tailPtr + numEnq

  // dequeue
  val numDeqTry = Mux(dispatchEntries > deqnum.U, deqnum.U, dispatchEntries)
  val numDeqFire = PriorityEncoder((io.deq.zipWithIndex map { case (deq, i) =>
    !deq.fire() && stateEntries(deqIndex(i)) === s_valid
  }) :+ true.B)
  val numDeq = Mux(numDeqTry > numDeqFire, numDeqFire, numDeqTry)
  for (i <- 0 until deqnum) {
    io.deq(i).bits := uopEntries(deqIndex(i))
    // needs to cancel uops trying to dequeue
    io.deq(i).valid := stateEntries(deqIndex(i)) === s_valid && !io.redirect.valid
  }

  // replay
  val needReplay = Wire(Vec(size, Bool()))
  // TODO: this is unaccptable since it need to add 64 bits
  val numReplay = PopCount(needReplay)
  for (i <- 0 until size) {
    needReplay(i) := roqNeedFlush(i) && stateEntries(i) === s_dispatched && io.redirect.bits.isReplay
    when (needReplay(i)) {
      stateEntries(i) := s_valid
    }
  }
  dispatchPtr := dispatchPtr + numDeq - numReplay

  // commit
  val numCommit = PopCount(io.commits.map(commit => !commit.bits.isWalk && commit.valid && commit.bits.uop.ctrl.dpqType === dpqType.U))
  val commitBits = (1.U((CommitWidth+1).W) << numCommit).asUInt() - 1.U
  for (i <- 0 until CommitWidth) {
    when (commitBits(i)) {
      stateEntries(commitIndex(i)) := s_invalid
    }
  }
  headPtr := headPtr + numCommit
}
