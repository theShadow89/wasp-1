package it.agilelab.bigdata.wasp.consumers.spark.plugins.kafka

import it.agilelab.bigdata.wasp.consumers.spark.readers.{SparkLegacyStreamingReader, SparkStructuredStreamingReader}
import it.agilelab.bigdata.wasp.consumers.spark.utils.SparkUtils
import it.agilelab.bigdata.wasp.core.WaspSystem
import it.agilelab.bigdata.wasp.core.WaspSystem.??
import it.agilelab.bigdata.wasp.core.bl.TopicBLImp
import it.agilelab.bigdata.wasp.core.kafka.CheckOrCreateTopic
import it.agilelab.bigdata.wasp.core.logging.Logging
import it.agilelab.bigdata.wasp.core.models.{StreamingReaderModel, StructuredStreamingETLModel, TopicModel}
import it.agilelab.bigdata.wasp.core.utils._
import kafka.serializer.{DefaultDecoder, StringDecoder}
import org.apache.avro.Schema
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka.KafkaUtils

import scala.collection.mutable

object KafkaSparkStructuredStreamingReader extends SparkStructuredStreamingReader with Logging {

  /**
    *
    * Create a Dataframe from a streaming source
    *
    * @param etl
    * @param streamingReaderModel
    * @param ss
    * @return
    */
  override def createStructuredStream(etl: StructuredStreamingETLModel,
                                      streamingReaderModel: StreamingReaderModel)
                                     (implicit ss: SparkSession): DataFrame = {

    logger.info(s"Creating stream from input: $streamingReaderModel of ETL: $etl")
    
    // extract the topic model
    logger.info(s"""Retrieving topic model with name "${streamingReaderModel.datastoreModelName}"""")
    val topic = new TopicBLImp(WaspDB.getDB).getByName(streamingReaderModel.datastoreModelName).get
    logger.info(s"Retrieved topic model: $topic")
    
    // get the config
    val kafkaConfig = ConfigManager.getKafkaConfig
    logger.info(s"Kafka configuration: $kafkaConfig")

    // check or create
    if (??[Boolean](
          WaspSystem.kafkaAdminActor,
          CheckOrCreateTopic(topic.name, topic.partitions, topic.replicas))) {
      // calculate maxOffsetsPerTrigger from trigger interval and rate limit
      // if the rate limit is set, calculate maxOffsetsPerTrigger as follows: if the trigger interval is unset, use
      // rate limit as is, otherwise multiply by triggerIntervalMs/1000
      // if the rate limit is not set, do not set maxOffsetsPerTrigger
      val triggerIntervalMs = SparkUtils.getTriggerIntervalMs(ConfigManager.getSparkStreamingConfig, etl)
      val maybeRateLimit: Option[Long] = streamingReaderModel.rateLimit.map(x => if (triggerIntervalMs == 0l) x else (triggerIntervalMs/1000d * x).toLong)
      val maybeMaxOffsetsPerTrigger = maybeRateLimit.map(rateLimit => ("maxOffsetsPerTrigger", rateLimit.toString))
      
      // calculate the options for the DataStreamReader
      val options = mutable.Map.empty[String, String]
      // start with the base options
      options ++= Seq(
        "subscribe" -> topic.name,
        "kafka.bootstrap.servers" -> kafkaConfig.connections.map(_.toString).mkString(","),
        "kafkaConsumer.pollTimeoutMs" -> kafkaConfig.ingestRateToMills().toString
      )
      // apply rate limit if it exists
      options ++= maybeMaxOffsetsPerTrigger
      // layer on the options coming from the kafka config "others" field
      options ++= kafkaConfig.others.map(_.toTupla).toMap
      // layer on the options coming from the streamingReaderModel
      options ++= streamingReaderModel.options
      logger.info(s"Final options to be pushed to DataStreamReader: $options")

      // create the stream
      val df: DataFrame = ss.readStream
        .format("kafka")
        .options(options)
        .load()

      // prepare the udf
      val byteArrayToJson: Array[Byte] => String = StringToByteArrayUtil.byteArrayToString

      import org.apache.spark.sql.functions._

      val byteArrayToJsonUDF = udf(byteArrayToJson)

      val ret: DataFrame = topic.topicDataType match {
        case "avro" => {
          val rowConverter = AvroToRow(topic.getJsonSchema)
          val encoderForDataColumns = RowEncoder(rowConverter.getSchemaSpark().asInstanceOf[StructType])
          df.select("value").map((r: Row) => {
            val avroByteValue = r.getAs[Array[Byte]](0)
            rowConverter.read(avroByteValue)
          })(encoderForDataColumns)
        }
        case "json" => {
          df.withColumn("value_parsed", byteArrayToJsonUDF(col("value")))
            .drop("value")
            .select(from_json(col("value_parsed"), topic.getDataType).alias("value"))
            .select(col("value.*"))
        }
        case "plaintext" => {
          df
            .select(col("value"))
        }
        case _ => throw new Exception(s"No such topic data type ${topic.topicDataType}")
      }
      logger.debug(s"Kafka reader avro schema: ${new Schema.Parser().parse(topic.getJsonSchema).toString(true)}")
      logger.debug(s"Kafka reader spark schema: ${ret.schema.treeString}")
      ret

    } else {
      val msg = s"Topic not found on Kafka: $topic"
      logger.error(msg)
      throw new Exception(msg)
    }
  }
}

object KafkaSparkLegacyStreamingReader extends SparkLegacyStreamingReader with Logging {

  /**
    * Kafka configuration
    */
  //TODO: check warning (not understood)
  def createStream(group: String, accessType: String, topic: TopicModel)(
      implicit ssc: StreamingContext): DStream[String] = {
    val kafkaConfig = ConfigManager.getKafkaConfig

    val kafkaConfigMap: Map[String, String] = (
      Seq(
        "zookeeper.connect" -> kafkaConfig.zookeeperConnections.toString(),
        "zookeeper.connection.timeout.ms" ->
          kafkaConfig.zookeeperConnections.connections.headOption.flatMap(_.timeout)
            .getOrElse(ConfigManager.getWaspConfig.servicesTimeoutMillis)
            .toString) ++
        kafkaConfig.others.map(_.toTupla)
      )
      .toMap

    if (??[Boolean](
          WaspSystem.kafkaAdminActor,
          CheckOrCreateTopic(topic.name, topic.partitions, topic.replicas))) {

      val receiver: DStream[(String, Array[Byte])] = accessType match {
        case "direct" =>
          KafkaUtils.createDirectStream[String,
                                        Array[Byte],
                                        StringDecoder,
                                        DefaultDecoder](
            ssc,
            kafkaConfigMap + ("group.id" -> group) + ("metadata.broker.list" -> kafkaConfig.connections
              .mkString(",")),
            Set(topic.name)
          )
        case "receiver-based" | _ =>
          KafkaUtils
            .createStream[String, Array[Byte], StringDecoder, DefaultDecoder](
              ssc,
              kafkaConfigMap + ("group.id" -> group),
              Map(topic.name -> 3),
              StorageLevel.MEMORY_AND_DISK_2
            )
      }
      val topicSchema = JsonConverter.toString(topic.schema.asDocument())
      topic.topicDataType match {
        case "avro" =>
          receiver.map(x => (x._1, AvroToJsonUtil.avroToJson(x._2, topicSchema))).map(_._2)
        case "json" | "plaintext" =>
          receiver
            .map(x => (x._1, StringToByteArrayUtil.byteArrayToString(x._2)))
            .map(_._2)
        case _ =>
          receiver.map(x => (x._1, AvroToJsonUtil.avroToJson(x._2, topicSchema))).map(_._2)
      }

    } else {
      val msg = s"Topic not found on Kafka: $topic"
      logger.error(msg)
      throw new Exception(msg)
    }
  }
}