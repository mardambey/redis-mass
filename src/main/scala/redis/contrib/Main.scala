package redis.contrib

import redis.clients.jedis.{ Pipeline, JedisPool, JedisPoolConfig }
import redis.clients.jedis.exceptions.JedisConnectionException
import scala.io.Source
import java.io.File
import java.net.URI
import java.util.Date
import scala.util.{ Success, Try }
import com.typesafe.config.ConfigFactory

object Main extends App {

  if (args.length < 2) {
    println("Not enough parameters specified.")
    usage
    sys.exit()
  }

  val conf = ConfigFactory.load()
  val redisServer = conf.getString("redis-mass.redis-server-uri")
  val redisServerUri = new URI(redisServer)
  val batchSize = conf.getInt("redis-mass.batch-size")
  val commandsFile = args(0)
  val command = args(1)

  val fn: (Pipeline, Seq[String]) ⇒ Unit = command match {
    case "del" ⇒ {
      println("Running in delete mode.")
      delete
    }

    case "set" ⇒ {
      if (args.length < 3) {
        println("Please provide a separator string")
        usage()
        sys.exit()
      }
      println("Running in set mode.")
      set(args(2))
    }

    case "setBitBoolean" ⇒ {
      if (args.length < 3) {
        println("Please provide a separator string")
        usage()
        sys.exit()
      }
      println("Running in set mode.")
      setBitBoolean(args(2))
    }

    case "expire" ⇒ {
      if (args.length < 3) {
        println("Asked to expire but not given a timeout in seconds.")
        usage()
        sys.exit()
      }

      println(s"Setting TTL on keys to ${args(2)} seconds.")
      expire(args(2).toInt)
    }

    case _ ⇒ {
      println("No valid command provided.")
      usage()
      sys.exit()
    }
  }

  val pool = new JedisPool(redisServerUri)

  if (!new File(commandsFile).exists()) {
    println(s"Could not find commands file: $commandsFile")
    sys.exit()
  }

  var b = 0

  Source.fromFile(commandsFile).getLines().grouped(batchSize).foreach(
    batch ⇒ {
      val jedis = pool.getResource
      val pipeline = jedis.pipelined()
      b += 1

      try {
        println(s"Processing batch $b on ${new Date()}")
        fn(pipeline, batch)
        pipeline.sync()
      } catch {
        case e: JedisConnectionException ⇒ { if (null != jedis) pool.returnBrokenResource(jedis); println(s"Failed deleting batch $b") }
      } finally {
        if (jedis != null) pool.returnResource(jedis)
      }
    })

  pool.destroy()

  def usage() {
    println(s"Usage: redis-mass /path/to/file/with/redis/keys <command> [options]")
    println("  set separator             set keys from file")
    println("  setBitBoolean separator   set keys from file")
    println("  del                       delete keys from file")
    println("  expire ttl                expire keys from file")
  }

  def set(separator: String)(pipeline: Pipeline, keysAndValues: Seq[String]) = {
    keysAndValues.foreach(splitToTuple(_, separator) match {
      case (key: String, value: String) ⇒ pipeline.set(key, value)
      case _                            ⇒ ()
    })
  }

  def setBitBoolean(separator: String)(pipeline: Pipeline, keysAndValues: Seq[String]) = {
    keysAndValues.foreach(splitToTuple(_, separator) match {
      case (key: String, offset: String, value: String) ⇒ (Try(offset.toLong), Try(value.toBoolean)) match {
        case (Success(longOffset), Success(booleanValue)) ⇒ pipeline.setbit(key, longOffset, booleanValue)
        case _ ⇒ ()
      }
      case _ ⇒ ()
    })
  }

  def delete(pipeline: Pipeline, key: Seq[String]) {
    // Jedis is not playingn nice with scala here...
    pipeline.del(key: _*)
  }

  def expire(timeout: Int)(pipeline: Pipeline, key: Seq[String]) {
    key.foreach(pipeline.expire(_, timeout))
  }

  def splitToTuple(string: String, separator: String) = {
    string.split(separator) match {
      case Array(str1, str2, str3) ⇒ (str1, str2, str3)
      case Array(str1, str2)       ⇒ (str1, str2)
      case Array(str1)             ⇒ (str1, "")
      case array                   ⇒ (array.head, array.tail.mkString(separator))
    }
  }
}
