/**
 * @author Sowmya Dixit
 */
package org.ekstep.analytics.model

import org.ekstep.analytics.framework.MeasuredEvent
import org.ekstep.analytics.framework.IBatchModelTemplate
import org.ekstep.analytics.creation.model.CreationEvent
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.ekstep.analytics.framework.util.JobLogger
import org.ekstep.analytics.framework.DataFilter
import org.ekstep.analytics.framework.Filter
import scala.collection.mutable.Buffer
import org.apache.spark.HashPartitioner
import org.ekstep.analytics.framework.JobContext
import org.ekstep.analytics.framework.AlgoInput
import org.ekstep.analytics.framework.util.CommonUtil
import org.ekstep.analytics.framework.DtRange
import org.ekstep.analytics.framework._
import org.ekstep.analytics.creation.model.CreationEData
import org.ekstep.analytics.creation.model.CreationEks
import org.ekstep.analytics.framework.util.JSONUtils
import org.ekstep.analytics.util.Constants

/**
 * Case Classes for the data product
 */
case class PortalSessionInput(sid: String, filteredEvents: Buffer[CreationEvent]) extends AlgoInput
case class PageSummary(id: String, `type`: String, env: String, time_spent: Double, visit_count: Long)
case class EnvSummary(env: String, time_spent: Double, count: Long)
case class PortalSessionOutput(sid: String, uid: String, anonymousUser: Boolean, dtRange: DtRange,
                               start_time: Long, end_time: Long, time_spent: Double, time_diff: Double, page_views_count: Long,
                               first_visit: Boolean, ce_visits: Long, interact_events_count: Long, interact_events_per_min: Double,
                               env_summary: Option[Iterable[EnvSummary]], events_summary: Option[Iterable[EventSummary]],
                               page_summary: Option[Iterable[PageSummary]]) extends AlgoOutput

/**
 * @dataproduct
 * @Summarizer
 *
 * AppSessionSummaryModel
 *
 * Functionality
 * 1. Generate app specific session summary events. This would be used to compute app usage metrics.
 * Events used - BE_OBJECT_LIFECYCLE, CP_SESSION_START, CE_START, CE_END, CP_INTERACT & CP_IMPRESSION
 */
object AppSessionSummaryModel extends IBatchModelTemplate[CreationEvent, PortalSessionInput, PortalSessionOutput, MeasuredEvent] with Serializable {

    implicit val className = "org.ekstep.analytics.model.AppSessionSummaryModel"
    override def name: String = "AppSessionSummaryModel"

    override def preProcess(data: RDD[CreationEvent], config: Map[String, AnyRef])(implicit sc: SparkContext): RDD[PortalSessionInput] = {
        JobLogger.log("Filtering Events of BE_OBJECT_LIFECYCLE, CP_SESSION_START, CE_START, CE_END, CP_INTERACT, CP_IMPRESSION")
        val filteredData = DataFilter.filter(data, Array(Filter("context", "ISNOTEMPTY", None), Filter("eventId", "IN", Option(List("BE_OBJECT_LIFECYCLE", "CP_SESSION_START", "CP_INTERACT", "CP_IMPRESSION", "CE_START", "CE_END")))));
        filteredData.map(event => (event.context.get.sid, Buffer(event)))
            .partitionBy(new HashPartitioner(JobContext.parallelization))
            .reduceByKey((a, b) => a ++ b).mapValues { events => events.sortBy { x => x.ets } }
            .map { x => PortalSessionInput(x._1, x._2) }
    }

    override def algorithm(data: RDD[PortalSessionInput], config: Map[String, AnyRef])(implicit sc: SparkContext): RDD[PortalSessionOutput] = {

        val idleTime = config.getOrElse("idleTime", 600).asInstanceOf[Int];

        data.map { x =>
            val events = x.filteredEvents
            val firstEvent = events.head;
            val lastEvent = events.last;
            val telemetryVer = firstEvent.ver;
            val startTimestamp = firstEvent.ets;
            val endTimestamp = lastEvent.ets;
            val uid = if (lastEvent.uid.isEmpty()) "" else lastEvent.uid
            val isAnonymous = if (uid.isEmpty()) true else false
            val lifeCycleEvent = events.filter { x => "BE_OBJECT_LIFECYCLE".equals(x.eid) && "User".equals(x.edata.eks.`type`) && "Create".equals(x.edata.eks.state) }
            val firstVisit = if (lifeCycleEvent.size > 0) true else false
            val timeDiff = CommonUtil.roundDouble(CommonUtil.getTimeDiff(startTimestamp, endTimestamp).get, 2);

            var tmpLastEvent: CreationEvent = null;
            val eventsWithTs = events.map { x =>
                if (tmpLastEvent == null) tmpLastEvent = x;
                val ts = CommonUtil.getTimeDiff(tmpLastEvent.ets, x.ets).get;
                tmpLastEvent = x;
                (x, if (ts > idleTime) 0 else ts)
            }
            val timeSpent = CommonUtil.roundDouble(eventsWithTs.map(f => f._2).sum, 2);
            val impressionEvents = events.filter { x => "CP_IMPRESSION".equals(x.eid) }
            val pageViewsCount = impressionEvents.size.toLong
            val ceVisits = events.filter { x => "CE_START".equals(x.eid) }.size.toLong
            val interactEventsCount = events.filter { x => "CP_INTERACT".equals(x.eid) }.size.toLong
            val interactEventsPerMin: Double = if (interactEventsCount == 0 || timeSpent == 0) 0d
            else if (timeSpent < 60.0) interactEventsCount.toDouble
            else BigDecimal(interactEventsCount / (timeSpent / 60)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble;

            val eventSummaries = events.groupBy { x => x.eid }.map(f => EventSummary(f._1, f._2.length));

            val impressionCEEvents = events.filter { x => ("CP_IMPRESSION".equals(x.eid) || "CE_START".equals(x.eid)) }.map { f =>
                if ("CE_START".equals(f.eid)) {
                    val eksString = JSONUtils.serialize(Map("env" -> "content-editor", "type" -> "", "id" -> "ce"))
                    val eks = JSONUtils.deserialize[CreationEks](eksString)
                    CreationEvent("CP_IMPRESSION", f.ets, f.ver, f.mid, f.pdata, f.cdata, f.uid, f.context, f.rid, new CreationEData(eks), f.tags)
                } else f;
            }

            val pageSummaries = if (impressionCEEvents.length > 0) {
                var prevPage: CreationEvent = impressionCEEvents(0);
                val pageDetails = impressionCEEvents.tail.map { x =>
                    val timeSpent = CommonUtil.getTimeDiff(prevPage.ets, x.ets).get;
                    val finalts = if (timeSpent > idleTime) 0d else timeSpent
                    val pageWithTs = (prevPage.edata.eks.id, prevPage, finalts)
                    prevPage = x
                    pageWithTs;
                }
                val lastPage = impressionCEEvents.last
                val lastTimeSpent = CommonUtil.getTimeDiff(lastPage.ets, lastEvent.ets).get;
                val lastPageTimeSpent = if (lastTimeSpent > idleTime) 0d else lastTimeSpent
                val lastPageWithTs = (lastPage.edata.eks.id, lastPage, lastPageTimeSpent)
                val finalPageDetails = pageDetails ++ Buffer(lastPageWithTs)

                finalPageDetails.groupBy(f => f._1).map { f =>
                    val id = f._1
                    val firstEvent = f._2(0)._2
                    val `type` = firstEvent.edata.eks.`type`
                    val env = firstEvent.edata.eks.env
                    val timeSpent = CommonUtil.roundDouble(f._2.map(f => f._3).sum, 2)
                    val visitCount = f._2.length.toLong
                    PageSummary(id, `type`, env, timeSpent, visitCount)
                }
            } else Iterable[PageSummary]();

            val envSummaries = if (pageSummaries.size > 0) {
                pageSummaries.groupBy { x => x.env }.map { f =>
                    val timeSpent = f._2.map(x => x.time_spent).sum
                    val count = f._2.size.toLong
                    EnvSummary(f._1, timeSpent, count)
                }
            } else Iterable[EnvSummary]();

            PortalSessionOutput(x.sid, uid, isAnonymous, DtRange(startTimestamp, endTimestamp), startTimestamp, endTimestamp, timeSpent, timeDiff, pageViewsCount, firstVisit, ceVisits, interactEventsCount, interactEventsPerMin, Option(envSummaries), Option(eventSummaries), Option(pageSummaries))
        }.filter(f => (f.time_spent >= 1))
    }

    override def postProcess(data: RDD[PortalSessionOutput], config: Map[String, AnyRef])(implicit sc: SparkContext): RDD[MeasuredEvent] = {
        data.map { session =>
            val mid = CommonUtil.getMessageId("ME_APP_SESSION_SUMMARY", session.uid, "SESSION", session.dtRange);
            val measures = Map(
                "start_time" -> session.start_time,
                "end_time" -> session.end_time,
                "time_diff" -> session.time_diff,
                "time_spent" -> session.time_spent,
                "interact_events_count" -> session.interact_events_count,
                "interact_events_per_min" -> session.interact_events_per_min,
                "first_visit" -> session.first_visit,
                "ce_visits" -> session.ce_visits,
                "page_views_count" -> session.page_views_count,
                "env_summary" -> session.env_summary,
                "events_summary" -> session.events_summary,
                "page_summary" -> session.page_summary);
            MeasuredEvent("ME_APP_SESSION_SUMMARY", System.currentTimeMillis(), session.dtRange.to, "1.0", mid, session.uid, None, None,
                Context(PData(config.getOrElse("producerId", "AnalyticsDataPipeline").asInstanceOf[String], config.getOrElse("modelId", "AppSessionSummarizer").asInstanceOf[String], config.getOrElse("modelVersion", "1.0").asInstanceOf[String]), None, "SESSION", session.dtRange),
                Dimensions(None, None, None, None, None, None, None, None, Option(session.anonymousUser), None, None, None, None, None, Option(session.sid), None, None, None, None, None, None, None, None, Option(config.getOrElse("appId", Constants.DEFAULT_APP_ID).asInstanceOf[String])),
                MEEdata(measures), None);
        }
    }
}