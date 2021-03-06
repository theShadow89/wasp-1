package it.agilelab.bigdata.wasp.consumers.spark.utils

import it.agilelab.bigdata.wasp.consumers.spark.batch.StartBatchJobSender
import it.agilelab.bigdata.wasp.core.bl.ConfigBL
import it.agilelab.bigdata.wasp.core.models.BatchSchedulerModel
import org.quartz.CronScheduleBuilder._
import org.quartz.JobBuilder._
import org.quartz.TriggerBuilder._
import org.quartz.impl.StdSchedulerFactory
import org.quartz.{JobDetail, Scheduler, Trigger}

/**
	* Utilities for Quartz 2 scheduler.
	*
	* @author Nicolò Bidotti
	*/
object Quartz2Utils {
	def buildScheduler(): Scheduler = {
		// create standard scheduler
		val sf = new StdSchedulerFactory
		val scheduler = sf.getScheduler
		
		// scheduler will not execute jobs until it has been started
		scheduler.start()
		
		scheduler
	}
	
	implicit class BatchSchedulerModelQuartz2Support(schedulerModel: BatchSchedulerModel) {
		private val batchJobModel = ConfigBL.batchJobBL.getByName(schedulerModel.batchJob.get).get
		
		def getQuartzJob(sparkConsumersBatchMasterGuardianActorPath: String): JobDetail = {
			val job = newJob(classOf[StartBatchJobSender])
				.withIdentity(batchJobModel.name, batchJobModel.owner)
				.usingJobData("jobName", batchJobModel.name)
				.usingJobData("sparkConsumersBatchMasterGuardianActorPath", sparkConsumersBatchMasterGuardianActorPath)
				.build()
			
			job
		}
		
		def getQuartzTrigger: Trigger = {
			
			val trigger = newTrigger()
				.withIdentity(schedulerModel.name, batchJobModel.owner)
				.withSchedule(cronSchedule(schedulerModel.cronExpression))
				.startNow()
				.build()
			
			trigger
		}
	}
}