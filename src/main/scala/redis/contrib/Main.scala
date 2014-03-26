package redis.contrib

import redis.clients.jedis.{ Pipeline, Jedis, JedisPool, JedisPoolConfig }
import redis.clients.jedis.exceptions.JedisConnectionException
import scala.io.Source
import java.io.File
import java.util.Date

object Main extends App {

  if (args.length < 2) {
    println("Not enough parameters specified.")
    usage
    sys.exit()
  }

  val redisServer = "localhost"
  val batchSize = 1000
  val commandsFile = args(0)
  val command = args(1)

  val fn: (Pipeline, Seq[String]) ⇒ Unit = command match {
    case "del" ⇒ {
      println("Running in delete mode.")
      delete
    }

    case "expire" ⇒ {
      if (args.length < 3) {
        println("Asked to expire but not given a timeout in seconds.")
        usage
        sys.exit()
      }

      println(s"Setting TTL on keys to ${args(2)} seconds.")
      expire(args(2).toInt)
    }

    case _ ⇒ {
      println("No valid command provided.")
      usage
      sys.exit()
    }
  }

  val pool = new JedisPool(new JedisPoolConfig(), redisServer)

  if (!new File(commandsFile).exists()) {
    println(s"Could not find commands file: $commandsFile")
    sys.exit()
  }

  var b = 0

  Source.fromFile(commandsFile).getLines().grouped(batchSize).foreach(
    batch ⇒ {
      val jedis = pool.getResource()
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

  pool.destroy();

  def usage {
    println(s"Usage: redis-mass /path/to/file/with/redis/keys <command> [options]")
    println("  del         delete keys from file")
    println("  expire ttl  expire keys from file")
  }

  def delete(pipeline: Pipeline, key: Seq[String]) {
    // Jedis is not playingn nice with scala here...
    pipeline.del(key: _*)
  }

  def expire(timeout: Int)(pipeline: Pipeline, key: Seq[String]) {
    key.foreach(pipeline.expire(_, timeout))
  }
}
