package org.ekstep.analytics.api.util

import org.ekstep.analytics.framework.exception.DataFetcherException
import org.ekstep.analytics.framework._
import org.ekstep.analytics.framework.fetcher.S3DataFetcher
import org.ekstep.analytics.framework.util.JobLogger
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.ekstep.analytics.framework.util.S3Util
import scala.collection.mutable.Buffer

object DataFetcher {
	implicit val className = "org.ekstep.analytics.api.util.DataFetcher"
  	@throws(classOf[DataFetcherException])
    def fetchBatchData[T](search: Fetcher)(implicit mf: Manifest[T]): Array[T] = {
        JobLogger.log("Fetching data", Option(Map("query" -> search)))
        if (search.queries.isEmpty) {
            throw new DataFetcherException("Data fetch configuration not found")
        }
        val date = search.queries.get.last.endDate
        val data: Array[String] = search.`type`.toLowerCase() match {
            case "s3" =>
                JobLogger.log("Fetching the batch data from S3")
                S3DataFetcher.getObjectKeys(search.queries.get).toArray;
                
                val data = for(query <- search.queries.get) yield { 
                    S3Util.getObject(query.bucket.get, query.prefix.get)
                }
                data.flatMap { x => x.map { x => x } }
            case "local" =>
                JobLogger.log("Fetching the batch data from Local file")
                val keys = search.queries.get.map { x => x.file.getOrElse("") }.filterNot { x => x == null };
                val data = for(key <- keys) yield { 
                    //val isPath = scala.reflect.io.File(scala.reflect.io.Path(key)).exists
                    //if(isPath) scala.io.Source.fromFile(key).getLines().toArray else Array[String]()
                    scala.io.Source.fromFile(key).getLines().toArray
                }
                data.flatMap { x => x.map { x => x } }
            case _ =>
                throw new DataFetcherException("Unknown fetcher type found");
        }
        JobLogger.log("Deserializing Input Data"); 
        data.map(f => JSONUtils.deserialize[T](f))
    }
}