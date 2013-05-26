// This file is part of the "SQLTap" project
//   (c) 2011-2013 Paul Asmuth <paul@paulasmuth.com>
//
// Licensed under the MIT License (the "License"); you may not use this
// file except in compliance with the License. You may obtain a copy of
// the License at: http://opensource.org/licenses/MIT

package com.paulasmuth.sqltap.mysql

import com.paulasmuth.sqltap.{SQLTap,TemporaryException}
import java.nio.channels.{Selector}
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.HashMap

class SQLConnectionPool(config: HashMap[Symbol,String], _loop: Selector) {

  val loop : Selector = _loop

  var max_connections = 50
  var max_queue_len   = 100

  private val connections      = new ListBuffer[SQLConnection]()
  private val connections_idle = new ListBuffer[SQLConnection]()
  private val queue            = new ListBuffer[SQLQuery]()

  for (n <- (0 until max_connections))
    connect()

  def execute(query: SQLQuery) : Unit = {
    val connection = get

    if (connection == null) {
      if (queue.length >= max_queue_len)
        throw new TemporaryException("sql queue is full")

      queue += query
    } else {
      connection.execute(query)
    }
  }

  def ready(connection: SQLConnection) : Unit = {
    connections_idle += connection

    val pending = math.min(connections_idle.length, queue.length)

    for (n <- (0 until pending)) {
      val conn = get()

      if (conn != null)
        conn.execute(queue.remove(0))
    }
  }

  def close(connection: SQLConnection) : Unit = {
    connections      -= connection
    connections_idle -= connection
  }

  private def get() : SQLConnection = {
    if (connections.length < max_connections)
      connect()

    if (connections_idle.length > 0)
      return connections_idle.remove(0)

    return null
  }

  private def connect() : Unit = {
    val conn = new SQLConnection(this)

    if (config contains 'mysql_host)
      conn.hostname = config('mysql_host)

    if (config contains 'mysql_port)
      conn.port = config('mysql_port).toInt

    if (config contains 'mysql_user)
      conn.username = config('mysql_user)

    if (config contains 'mysql_pass)
      conn.password = config('mysql_pass)

    if (config contains 'mysql_db)
      conn.database = config('mysql_db)

    conn.connect()
    connections += conn
  }

}
