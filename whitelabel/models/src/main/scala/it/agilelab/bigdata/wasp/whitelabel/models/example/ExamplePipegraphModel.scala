package it.agilelab.bigdata.wasp.whitelabel.models.example

import it.agilelab.bigdata.wasp.core.models._

private[wasp] object ExamplePipegraphModel {

  lazy val pipegraph = PipegraphModel(
    name = "ExamplePipegraph",
    description = "Description of Example Pipegraph",
    owner = "user",
    isSystem = false,
    creationTime = System.currentTimeMillis,

    legacyStreamingComponents = List.empty,
    structuredStreamingComponents = List(
      StructuredStreamingETLModel(
	      name = "Write on console",
	      streamingInput = StreamingReaderModel.kafkaReader(
			      name = "Read from example topic",
			      topicModel = ExampleTopicModel.topic,
			      rateLimit = None
		      ),
	      staticInputs = List.empty,
	      streamingOutput = WriterModel.consoleWriter("console-writer"),
	      mlModels = List.empty,
	      strategy = None,
	      triggerIntervalMs = None,
	      options = Map()
      )
    ),
    rtComponents = List(),

    dashboard = None)
}