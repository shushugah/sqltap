// This file is part of the "SQLTap" project
//   (c) 2011-2013 Paul Asmuth <paul@paulasmuth.com>
//
// Licensed under the MIT License (the "License"); you may not use this
// file except in compliance with the License. You may obtain a copy of
// the License at: http://opensource.org/licenses/MIT
package com.paulasmuth.sqltap.mysql
import java.nio.channels.Selector

trait AbstractSQLConnectionPool {
  val loop : Selector
  def ready(conn: SQLConnection)
  def busy(conn: SQLConnection)
  def close(conn: SQLConnection)
  def binlog(event: BinlogEvent)
}
