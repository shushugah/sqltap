// This file is part of the "SQLTap" project
//   (c) 2011-2013 Paul Asmuth <paul@paulasmuth.com>
//
// Licensed under the MIT License (the "License"); you may not use this
// file except in compliance with the License. You may obtain a copy of
// the License at: http://opensource.org/licenses/MIT

package com.paulasmuth.sqltap

import scala.collection.mutable.{ListBuffer}
import java.nio.channels.{SocketChannel,SelectionKey}
import java.nio.{ByteBuffer,ByteOrder}
import java.net.{InetSocketAddress,ConnectException}

/**
  * @param pool     pool that owns this connection
  * @param hostname memcached-server's hostname
  * @param port     memcached-server's port number
  */
class MemcacheConnection(pool: MemcacheConnectionPool, hostname : String, port : Int) extends TimeoutCallback {

  private val CR = 13.toByte
  private val LF = 10.toByte
  private val SP = 32.toByte

  // states:
  // Uninitialized, Connecting, Idle, Cmd{Delete,Set,MGet}, Reading, Closed.
  private val MC_STATE_INIT       = 0 // just created, no connect() invoked yet (idle)
  private val MC_STATE_CONN       = 1 // just connected (idle)
  private val MC_STATE_IDLE       = 2 // idle (after a command)
  private val MC_STATE_CMD_DELETE = 4 //
  private val MC_STATE_CMD_SET    = 5 //
  private val MC_STATE_CMD_MGET   = 6 // executing a multi-key GET
  private val MC_STATE_READ       = 7 //
  private val MC_STATE_CLOSE      = 8 //

  private val MC_WRITE_BUF_LEN  = (65535 * 3)
  private val MC_READ_BUF_LEN   = (MC_WRITE_BUF_LEN * 8)

  private var state = MC_STATE_INIT
  private var last_event : SelectionKey = null
  private val read_buf = ByteBuffer.allocate(MC_READ_BUF_LEN)
  private val write_buf = ByteBuffer.allocate(MC_WRITE_BUF_LEN)
  write_buf.order(ByteOrder.LITTLE_ENDIAN)

  private val sock = SocketChannel.open()
  sock.configureBlocking(false)

  private var timer = TimeoutScheduler.schedule(1000, this)

  private var requests : List[CacheRequest] = null

  // buffer for the current value to be received
  private var cur_buf  : ElasticBuffer = null

  // length in bytes of the currently received value
  private var cur_len  = 0

  /**
   * @brief Asynchronously establishes connection to the Memcached server.
   *
   * @see ready(event: SelectionKey)
   */
  def connect() : Unit = {
    Statistics.incr('memcache_connections_open)

    val addr = new InetSocketAddress(hostname, port)
    sock.connect(addr)
    state = MC_STATE_CONN

    timer.start()

    sock
      .register(pool.loop, SelectionKey.OP_CONNECT)
      .attach(this)
  }

  /**
   * @brief Callback, invoked upon non-blocking connect() completion.
   *
   * Moves MemcacheConnection state from MC_STATE_CONN into MC_STATE_IDLE state.
   */
  def ready(event: SelectionKey) : Unit = {
    try {
      sock.finishConnect
    } catch {
      case e: ConnectException => {
        Logger.error("[Memcache] connection failed: " + e.toString, false)
        return close(e)
      }
    }

    idle(event)
  }

  /**
   * @brief Puts connection into ready state and then back into the idle pool.
   */
  private def idle(event: SelectionKey) : Unit = {
    timer.cancel()
    state = MC_STATE_IDLE
    event.interestOps(0)
    last_event = event
    requests = null
    pool.ready(this)
  }

  /**
   * @brief Retrieves values for multiple keys.
   *
   * @param keys list of keys to retrieve
   * @param _requests list of CacheRequest objects to store the values to
   */
  def execute_mget(keys: List[String], _requests: List[CacheRequest]) : Unit = {
    Logger.debug("[Memcache] mget: " + keys.mkString(", "))

    requests = _requests

    if (state != MC_STATE_IDLE)
      throw new ExecutionException("memcache connection busy")

    timer.start()

    write_buf.clear
    write_buf.put("get".getBytes)

    for (key <- keys) {
      write_buf.put(SP)
      write_buf.put(key.getBytes("UTF-8"))
    }

    write_buf.put(CR)
    write_buf.put(LF)
    write_buf.flip

    state = MC_STATE_CMD_MGET
    last_event.interestOps(SelectionKey.OP_WRITE)
  }

  def execute_set(key: String, request: CacheStoreRequest) : Unit = {
    Logger.debug("[Memcache] store: " + key)

    val buf = request.buffer.buffer
    val len = buf.position

    request.ready()

    if (len > MC_WRITE_BUF_LEN - 512) {
      // some hacky safe margin
      // minus 512 because of the first command line
      return;
    }

    buf.position(0)
    buf.limit(len)

    if (state != MC_STATE_IDLE)
      throw new ExecutionException("memcache connection busy")

    timer.start()

    // "set $key 0 $expiry $len\r\n$buf\r\n"
    write_buf.clear
    write_buf.put("set".getBytes)
    write_buf.put(SP)
    write_buf.put(key.getBytes("UTF-8"))
    write_buf.put(SP)
    write_buf.put('0'.toByte)
    write_buf.put(SP)
    write_buf.put(request.expire.toString.getBytes("UTF-8"))
    write_buf.put(SP)
    write_buf.put(len.toString.getBytes("UTF-8"))
    write_buf.put(CR)
    write_buf.put(LF)
    write_buf.put(buf)
    write_buf.put(CR)
    write_buf.put(LF)
    write_buf.flip

    state = MC_STATE_CMD_SET
    last_event.interestOps(SelectionKey.OP_WRITE)
  }

  def execute_delete(key: String) : Unit = {
    Logger.debug("[Memcache] delete: " + key)

    if (state != MC_STATE_IDLE)
      throw new ExecutionException("memcache connection busy")

    timer.start()

    write_buf.clear
    write_buf.put("delete".getBytes)
    write_buf.put(SP)
    write_buf.put(key.getBytes("UTF-8"))
    write_buf.put(CR)
    write_buf.put(LF)
    write_buf.flip

    state = MC_STATE_CMD_DELETE
    last_event.interestOps(SelectionKey.OP_WRITE)
  }

  /**
    * @brief Callback, invoked when underlying socket is non-blocking readable.
    *
    * Processes any incoming data, i.e. the response from the underlying
    * memcached server.
    */
  def read(event: SelectionKey) : Unit = {
    val chunk = sock.read(read_buf)

    if (chunk <= 0) {
      Logger.error("[Memcache] read end of file ", false)
      close(new ExecutionException("memcache connection closed"))
      return
    }

    var cur = 0
    var pos = 0

    while (cur < read_buf.position) {
      if (state == MC_STATE_READ) {
        cur = math.min(read_buf.position, pos + cur_len)

        cur_len -= cur - pos
        cur_buf.write(read_buf.array, pos, cur - pos)

        if (cur_len == 0) {
          cur += 2
          cur_buf.buffer.flip()
          state = MC_STATE_CMD_MGET
        }

        pos = cur
      } else {
        if (read_buf.get(cur) == LF) {
          val headline = new String(read_buf.array, pos, cur - 1 - pos, "UTF-8")
          next(headline)
          pos = cur + 1
        }

        cur += 1
      }
    }

    if (cur < read_buf.position) {
      println("READ REMAINING")
      read_buf.limit(read_buf.position)
      read_buf.position(cur)
      read_buf.compact()
    } else {
      read_buf.clear()
    }
  }

  /**
    * @brief Callback, invoked when underlying connection is non-blocking
    *        writable.
    *
    * When all data has been flushed out to the memcached server,
    * we will stop watching for WRITE events and switch back to READ.
    */
  def write(event: SelectionKey) : Unit = {
    try {
      sock.write(write_buf)
    } catch {
      case e: Exception => {
        Logger.error("[Memcache] conn error: " + e.toString, false)
        return close(e)
      }
    }

    if (write_buf.remaining == 0) {
      write_buf.clear
      event.interestOps(SelectionKey.OP_READ)
    }
  }

  def close(err: Throwable = null) : Unit = {
    if (state == MC_STATE_CLOSE)
      return

    try {
      if (requests != null) {
        for (req <- requests) {
          req.ready()
        }
      }
    } catch {
      case e: Exception => {
        Logger.exception(e, false)
      }
    }

    state = MC_STATE_CLOSE

    pool.close(this)
    sock.close()
    Statistics.decr('memcache_connections_open)
  }

  def timeout() : Unit = {
    Logger.error("[Memcache] connection timed out...", false)
    close()
  }

  /** @brief Retrieves the corresponding CacheRequest to the given @p key.
    *
    * @return never null but the CacheRequest object.
    */
  private def get_request_by_key(key: String) : CacheRequest = {
    for (req <- requests) {
      if (req.buffer == null && req.key == key) {
        return req
      }
    }
    throw new ExecutionException("[Memcache] invalid response key: " + key)
  }

  private def next(cmd: String) : Unit = {
    state match {
      case MC_STATE_CMD_DELETE => cmd match {
        case "DELETED" => idle(last_event)
        case "NOT_FOUND" => idle(last_event)
      }
      case MC_STATE_CMD_SET => cmd match {
        case "STORED" => idle(last_event)
        case "NOT_STORED" => idle(last_event)
      }
      case MC_STATE_CMD_MGET => {
        val parts = cmd.split(" ")

        if (parts.length == 1 && parts.head == "END") {
          for (req <- requests) {
            req.ready()
          }

          return idle(last_event)
        }

        // expect ["VALUE", key, 0, dataLength]
        if (parts.length != 4) {
          throw new ExecutionException("[Memcache] protocol error: " + cmd)
        }

        val req = get_request_by_key(parts(1))
        cur_buf = new ElasticBuffer(65535 * 8)
        cur_len = parts(3).toInt
        req.buffer = cur_buf

        state = MC_STATE_READ
      }
      case _ => {
        throw new ExecutionException("unexpected token " + cmd +
                                     " (" + state.toString + ")")
      }
    }
  }
}
