package com.paulasmuth.dpump

import scala.collection.mutable.ListBuffer;

class RequestExecutor(req: Request) {

  var stack = ListBuffer[Instruction]()

  def run ={
    build(req.stack.root); 

    if (DPump.debug) {
      DPump.log_debug("Execution stack:")

      for (instruction <- stack)
        instruction.inspect(1)
    }

    next
  }

  private def build(cur: Instruction) : Unit = {
    for (next <- cur.next) { 
      next.name match {

        case "fetch" =>
          stack.head.args += cur.args.head

        case "fetch_all" =>
          stack.head.args += "*"

        case "findOne" => {
          next.object_id = next.args.remove(1).toInt
          stack += next
          build(next)
        }

      }
    }
    //req.stack.inspect
  }

  private def next() : Unit = {
    for (idx <- (stack.length-1) to 0)
      if (stack(idx).job == null)
        execute(stack(idx))

    if (stack.head.job != null) {
      stack.head.job.retrieve

      if (DPump.debug) {
        val qtime = (stack.head.job.result.qtime / 1000000.0).toString
        DPump.log_debug("Execute (" + qtime + "ms): "  + stack.head.job.query)
      }
    }

    stack.remove(0)

    if (stack.length > 0)
      next
  }

  private def execute(cur: Instruction) : Unit = {
    cur.name match {

    case "findOne" => {
      if (cur.object_id == 0) return
      val resource = DPump.manifest(cur.args(0))
      val query = SQLBuilder.sql_find_one(resource, List("*"), cur.object_id)
      cur.job = DPump.db_pool.execute(query)
    }

  }}

}
