package it.agilelab.bigdata.wasp.core.models.configuration

import it.agilelab.bigdata.wasp.core.models.Model

/**
	* Configuration model for HBase.
	*
	*/
case class HBaseConfigModel(
														 coreSiteXmlPath: String,
														 hbaseSiteXmlPath: String,
														 others: Seq[HBaseEntryConfig],
														 name: String
                           ) extends Model

case class HBaseEntryConfig(
														 key: String,
														 value: String
													 )