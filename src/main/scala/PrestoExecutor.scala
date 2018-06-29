package org.sparkall

import java.sql.DriverManager
import java.util

import com.google.common.collect.ArrayListMultimap
import model.DataQueryFrame
import org.apache.spark.sql.DataFrame
import org.sparkall.Helpers._

import scala.collection.mutable
import scala.collection.mutable.{HashMap, ListBuffer, Set}

class PrestoExecutor(prestoURI: String, mappingsFile: String) extends QueryExecutor[DataQueryFrame] {

    def getType() = {
        val dataframe : DataQueryFrame = null
        dataframe
    }

    def query(sources : Set[(HashMap[String, String], String, String)],
               optionsMap: HashMap[String, Map[String, String]],
               toJoinWith: Boolean,
               star: String,
               prefixes: Map[String, String],
               select: util.List[String],
               star_predicate_var: mutable.HashMap[(String, String), String],
               neededPredicates: Set[String],
               filters: ArrayListMultimap[String, (String, String)],
               leftJoinTransformations: (String, Array[String]),
               rightJoinTransformations: Array[String],
               joinPairs: Map[(String,String), String]
        ): (DataQueryFrame, Integer) = {

        //val spark = SparkSession.builder.master(sparkURI).appName("Sparkall").getOrCreate;
        //spark.sparkContext.setLogLevel("ERROR")
        //TODO: **PRESTO?** get from the function if there is a relevant data source that requires setting config to SparkSession

        // prestoURI = "jdbc:presto://localhost:8080"
        val connection = DriverManager.getConnection(prestoURI, "presto_user", null) // null: properties


        //var finalDF : String = null
        var finalDQF : DataQueryFrame = null
        var datasource_count = 0

        for (s <- sources) {
            println("\nNEXT SOURCE...")
            datasource_count += 1 // in case of multiple relevant data sources to union

            val attr_predicate = s._1
            println("Star: " + star)
            println("attr_predicate: " + attr_predicate)
            val sourcePath = s._2
            val sourceType = getTypeFromURI(s._3)
            val options = optionsMap(sourcePath)

            // TODO: move to another class better
            var columns = getSelectColumnsFromSet(attr_predicate, omitQuestionMark(star), prefixes, select, star_predicate_var, neededPredicates)

            println("Relevant source (" + datasource_count + ") is: [" + sourcePath + "] of type: [" + sourceType + "]")

            println("...from which columns (" + columns + ") are going to be projected")
            println("...with the following configuration options: " + options)

            if (toJoinWith) { // That kind of table that is the 1st or 2nd operand of a join operation
                val id = getID(sourcePath, mappingsFile)
                println("...is to be joined with using the ID: " + omitQuestionMark(star) + "_" + id + " (obtained from subjectMap)")
                if(columns == "") {
                    //println("heeey id = " + id + " star " + star)
                    columns = id + " AS " + omitQuestionMark(star) + "_ID"
                } else
                    columns = columns + "," + id + " AS " + omitQuestionMark(star) + "_ID"
            }

            println("sourceType: " + sourceType)

            var table = ""
            sourceType match {
                case "csv" => table = s"hive.csv.entity" // get entity
                case "parquet" => table = s"hive.hive.entity" // get entity
                case "cassandra" => table = s"""cassandra.${options("keyspace")}.${options("table")}"""
                case "elasticsearch" => table = ""
                case "mongodb" => table = s"""mongodb.${options("database")}.${options("collection")}"""
                case "jdbc" => s"""mysql.${options("database")}.${options("url").split("/")(3).split("?")(0)}""" // to get only DB from the URL
                //TODO: currently JDBC is only MySQL, fix this
                case _ =>
            }

            //val selected_table = s"SELECT $columns FROM $table."
            finalDQF.addSelect((columns,table))

            // Transformations SKIP FOR NOW
            /*if (leftJoinTransformations != null && leftJoinTransformations._2 != null) {
                val column: String = leftJoinTransformations._1
                println("leftJoinTransformations: " + column + " - " + leftJoinTransformations._2.mkString("."))
                val ns_pred = get_NS_predicate(column)
                val ns = prefixes(ns_pred._1)
                val pred = ns_pred._2
                val col = omitQuestionMark(star) + "_" + pred + "_" + ns
                finalDF = transform(finalDF, col, leftJoinTransformations._2)

            }
            if (rightJoinTransformations != null && !rightJoinTransformations.isEmpty) {
                println("rightJoinTransformations: " + rightJoinTransformations.mkString("_"))
                val col = omitQuestionMark(star) + "_ID"
                f*********inalDF = transform(finalDF, col, rightJoinTransformations)
            } */

        }

        println("\n- filters: " + filters + " ======= " + star)

        var whereString = ""

        var nbrOfFiltersOfThisStar = 0

        val it = filters.keySet().iterator()
        while (it.hasNext) {
            val value = it.next()
            val predicate = star_predicate_var.
                filter(t => t._2 == value).
                keys. // To obtain (star, predicate) pairs having as value the FILTER'ed value
                filter(t => t._1 == star).
                map(f => f._2).toList

            if (predicate.nonEmpty) {
                val ns_p = get_NS_predicate(predicate.head) // Head because only one value is expected to be attached to the same star an same (object) variable
                val column = omitQuestionMark(star) + "_" + ns_p._2 + "_" + prefixes(ns_p._1)
                println("--- Filter column: " + column)

                nbrOfFiltersOfThisStar = filters.get(value).size()

                val conditions = filters.get(value).iterator()
                while (conditions.hasNext) {
                    val operand_value = conditions.next()
                    println(s"--- Operand - Value: $operand_value")
                    whereString = column + operand_value._1 + operand_value._2
                    println(s"--- WHERE string: $whereString")

                    //println("colcolcol: " + finalDF(column).toString())
                    //println("operand_value._2: " + operand_value._2.replace("\"",""))
                    if (operand_value._1 != "regex")
                        finalDQF.addFilter(whereString)
                    else
                        finalDQF.addFilter(s" AND $column like ${operand_value._2.replace("\"","")}")
                        // regular expression with _ matching an arbitrary character and % matching an arbitrary sequence
                }
                //finalDF.show()
            }
        }

        println(s"Number of filters of this star is: $nbrOfFiltersOfThisStar")


        (finalDQF, nbrOfFiltersOfThisStar)
    }

    override def transform(ps: DataQueryFrame, column: String, transformationsArray: Array[String]) = null
    /*
        def transform(df: DataQueryFrame, column: String, transformationsArray : Array[String]): DataQueryFrame = {

            var ndf : DataQueryFrame = df
            for (t <- transformationsArray) {
                println("Transformation next: " + t)
                t match {
                    case "toInt" =>
                        println("TOINT found")
                        ndf = ndf.withColumn(column, ndf(column).cast(IntegerType))
                        // From SO: values not castable will become null
                    case s if s.contains("scl") =>
                        val scaleValue = s.replace("scl","").trim.stripPrefix("(").stripSuffix(")")
                        println("SCL found: " + scaleValue)
                        val operation = scaleValue.charAt(0)
                        operation match {
                            case '+' => ndf = ndf.withColumn(column, ndf(column) + scaleValue.substring(1).toInt)
                            case '-' => ndf = ndf.withColumn(column, ndf(column) - scaleValue.substring(1).toInt)
                            case '*' => ndf = ndf.withColumn(column, ndf(column) * scaleValue.substring(1).toInt)
                        }
                    case s if s.contains("skp") =>
                        val skipValue = s.replace("skp","").trim.stripPrefix("(").stripSuffix(")")
                        println("SKP found: " + skipValue)
                        ndf = ndf.filter(!ndf(column).equalTo(skipValue))
                    case s if s.contains("substit") =>
                        val replaceValues = s.replace("substit","").trim.stripPrefix("(").stripSuffix(")").split("\\,")
                        val valToReplace = replaceValues(0)
                        val valToReplaceWith = replaceValues(1)
                        println("SUBSTIT found: " + replaceValues.mkString(" -> "))
                        ndf = ndf.withColumn(column, when(col(column).equalTo(valToReplace), valToReplaceWith))
                        //ndf = df.withColumn(column, when(col(column) === valToReplace, valToReplaceWith).otherwise(col(column)))
                    case s if s.contains("replc") =>
                        val replaceValues = s.replace("replc","").trim.stripPrefix("(").stripSuffix(")").split("\\,")
                        val valToReplace = replaceValues(0).replace("\"","")
                        val valToReplaceWith = replaceValues(1).replace("\"","")
                        println("REPLC found: " + replaceValues.mkString(" -> ") + " on column: " + column)
                        ndf = ndf.withColumn(column, when(col(column).contains(valToReplace), regexp_replace(ndf(column), valToReplace, valToReplaceWith)))
                    case s if s.contains("prefix") =>
                        val prefix = s.replace("prfix","").trim.stripPrefix("(").stripSuffix(")")
                        println("PREFIX found: " + prefix)
                        ndf = ndf.withColumn(column, concat(lit(prefix), ndf.col(column)))
                    case s if s.contains("postfix") =>
                        val postfix = s.replace("postfix","").trim.stripPrefix("(").stripSuffix(")")
                        println("POSTFIX found: " + postfix)
                        ndf = ndf.withColumn(column, concat(lit(ndf.col(column), postfix)))
                    case _ =>
                }
            }

            ndf
        }
    */

    def join(joins: ArrayListMultimap[String, (String, String)], prefixes: Map[String, String], star_df: Map[String, DataQueryFrame]): DataQueryFrame = {
        import scala.collection.JavaConversions._
        import scala.collection.mutable.ListBuffer

        var pendingJoins = mutable.Queue[(String, (String, String))]()
        val seenDF : ListBuffer[(String,String)] = ListBuffer()
        var firstTime = true
        val join = " x "
        val jDQF : DataQueryFrame = null

        val it = joins.entries.iterator
        while (it.hasNext) {
            val entry = it.next

            val table1 = entry.getKey
            val table2 = entry.getValue._1
            val jVal = entry.getValue._2
            // TODO: add omitQuestionMark and omit it from the next

            println(s"-> GOING TO JOIN ($table1 $join $table2) USING $jVal...")

            val njVal = get_NS_predicate(jVal)
            val ns = prefixes(njVal._1)

            println("njVal: " + ns)

            it.remove

            //val df1 = star_df(table1)
            //val df2 = star_df(table2)

            if (firstTime) { // First time look for joins in the join hashmap
                println("...that's the FIRST JOIN")
                seenDF.add((table1, jVal))
                seenDF.add((table2, "ID"))
                firstTime = false

                // Join level 1
                jDQF.addJoin((table1,table2,omitQuestionMark(table1) + "_" + omitNamespace(jVal) + "_" + ns,omitQuestionMark(table2) + "_ID"))
                //jDQF = df1.join(df2, df1.col(omitQuestionMark(table1) + "_" + omitNamespace(jVal) + "_" + ns).equalTo(df2(omitQuestionMark(table2) + "_ID")))
                println("...done")
            } else {
                val dfs_only = seenDF.map(_._1)
                println(s"EVALUATING NEXT JOIN \n ...checking prev. done joins: $dfs_only")
                if (dfs_only.contains(table1) && !dfs_only.contains(table2)) {
                    println("...we can join (this direction >>)")

                    val leftJVar = omitQuestionMark(table1) + "_" + omitNamespace(jVal) + "_" + ns
                    val rightJVar = omitQuestionMark(table2) + "_ID"
                    jDQF.addJoin(("",table2,leftJVar,rightJVar))
                    //jDQF = jDQF.join(df2, jDQF.col(leftJVar).equalTo(df2.col(rightJVar)))

                    seenDF.add((table2,"ID"))

                    //println("Nbr: " + jDQF.count)
                    //jDQF.show()
                } else if (!dfs_only.contains(table1) && dfs_only.contains(table2)) {
                    println("...we can join (this direction >>)")

                    val leftJVar = omitQuestionMark(table1) + "_" + omitNamespace(jVal) + "_" + ns
                    val rightJVar = omitQuestionMark(table2) + "_ID"
                    jDQF.addJoin((table1,"",leftJVar,rightJVar))
                    //jDQF = df1.join(jDQF, df1.col(leftJVar).equalTo(jDQF.col(rightJVar)))

                    seenDF.add((table1,jVal))

                    //println("Nbr: " + jDQF.count)
                    //jDQF.show()
                } else if (!dfs_only.contains(table1) && !dfs_only.contains(table2)) {
                    println("...no join possible -> GOING TO THE QUEUE")
                    pendingJoins.enqueue((table1, (table2, jVal)))
                }
                // TODO: add case of if dfs_only.contains(table1) && dfs_only.contains(table2)
            }
        }

        while (pendingJoins.nonEmpty) {
            println("ENTERED QUEUED AREA: " + pendingJoins)
            val dfs_only = seenDF.map(_._1)

            val e = pendingJoins.head

            val table1 = e._1
            val table2 = e._2._1
            val jVal = e._2._2

            val njVal = get_NS_predicate(jVal)
            val ns = prefixes(njVal._1)

            println(s"-> Joining ($table1 $join $table2 + ) using $jVal...")

            //val df1 = star_df(table1)
            //val df2 = star_df(table2)

            if (dfs_only.contains(table1) && !dfs_only.contains(table2)) {
                val leftJVar = omitQuestionMark(table1) + "_" + omitNamespace(jVal) + "_" + ns
                val rightJVar = omitQuestionMark(table2) + "_ID"
                jDQF.addJoin(("",table2,leftJVar,rightJVar))
                //jDQF = jDQF.join(df2, jDQF.col(leftJVar).equalTo(df2.col(rightJVar))) // deep-left
                //jDQF = df2.join(jDQF, jDQF.col(leftJVar).equalTo(df2.col(rightJVar)))

                seenDF.add((table2,"ID"))
            } else if (!dfs_only.contains(table1) && dfs_only.contains(table2)) {
                val leftJVar = omitQuestionMark(table1) + "_" + omitNamespace(jVal) + "_" + ns
                val rightJVar = omitQuestionMark(table2) + "_ID"
                jDQF.addJoin((table1,"",leftJVar,rightJVar))
                //jDQF = jDQF.join(df1, df1.col(leftJVar).equalTo(jDQF.col(rightJVar))) // deep-left
                //jDQF = df1.join(jDQF, df1.col(leftJVar).equalTo(jDQF.col(rightJVar)))

                seenDF.add((table1,jVal))
            } else if (!dfs_only.contains(table1) && !dfs_only.contains(table2)) {
                pendingJoins.enqueue((table1, (table2, jVal)))
            }

            pendingJoins = pendingJoins.tail
        }

        jDQF
    }

    def joinReordered(joins: ArrayListMultimap[String, (String, String)], prefixes: Map[String, String], star_df: Map[String, DataFrame], startingJoin: (String, (String, String)), starWeights: Map[String, Double]): String = {
        null
    }

    def project(jDQF: Any, columnNames: Seq[String], distinct: Boolean) : DataQueryFrame = {
        if(!distinct)
            jDQF.asInstanceOf[DataQueryFrame].addProject((columnNames, false))
        else
            jDQF.asInstanceOf[DataQueryFrame].addProject((columnNames, true))

        jDQF.asInstanceOf[DataQueryFrame]
    }

    def schemaOf(jDF: DataQueryFrame) = {
        null // TODO: prntiSchema in Presto?
    }

    def count(jDQF: DataQueryFrame): Long = {
        -12345789 // TODO: think about COUNT in Presto
    }

    def orderBy(jDQF: Any, direction: String, variable: String) : DataQueryFrame = {
        println("ORDERING...")

        if (direction == "-1") {
            jDQF.asInstanceOf[DataQueryFrame].addOrderBy((variable,1))  // 1: asc
            //jDF.orderBy(asc(variable))
        } else { // TODO: assuming the other case is automatically -1 IFNOT change to "else if (direction == "-2") {"
            jDQF.asInstanceOf[DataQueryFrame].addOrderBy((variable,-1)) // 2: desc
            //jDF.orderBy(desc(variable))
        }

        jDQF.asInstanceOf[DataQueryFrame]
    }

    def groupBy(jDQF: Any, groupBys: (ListBuffer[String], Set[(String,String)])): DataQueryFrame = {

        val groupByVars = groupBys._1
        val aggregationFunctions = groupBys._2

        println("aggregationFunctions: " + aggregationFunctions)

        var aggSet : Set[(String,String)] = Set()
        for (af <- aggregationFunctions){
            aggSet += ((af._1,af._2))
        }
        val agg = aggSet.toList

        jDQF.asInstanceOf[DataQueryFrame].addGroupBy(groupByVars)
        jDQF.asInstanceOf[DataQueryFrame].addAggregate(agg)

        // eg df.groupBy("department").agg(max("age"), sum("expense"))
        // ("o_price_cbo","sum"),("o_price_cbo","max")
        //newJDF.printSchema()

        jDQF.asInstanceOf[DataQueryFrame]
    }

    def limit(jDQF: Any, limitValue: Int) : DataQueryFrame = {
        jDQF.asInstanceOf[DataQueryFrame].addLimit(limitValue)

        jDQF.asInstanceOf[DataQueryFrame]
    }

    def show(jDF: Any) = null

}
