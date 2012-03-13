/**
 * Copyright (c) 2002-2012 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher

import internal.commands._
import internal.executionplan.ExecutionPlanImpl
import internal.LRUCache
import scala.collection.JavaConverters._
import java.lang.Error
import java.util.{Map => JavaMap}
import scala.deprecated
import org.neo4j.kernel.AbstractGraphDatabase
import org.neo4j.graphdb.GraphDatabaseService

class ExecutionEngine(graph: GraphDatabaseService) {
  checkScalaVersion()

  require(graph != null, "Can't work with a null graph database")

  val parser = createCorrectParser()

  private def createCorrectParser() = if (graph.isInstanceOf[AbstractGraphDatabase]) {
    val database = graph.asInstanceOf[AbstractGraphDatabase]
    database.getConfig.getParams.asScala.get("cypher_parser_version") match {
      case None => new CypherParser()
      case Some(v) => new CypherParser(v.toString)
    }
  }
  else {
    new CypherParser()
  }


  @throws(classOf[SyntaxException])
  def execute(query: String): ExecutionResult = execute(query, Map[String, Any]())

  @throws(classOf[SyntaxException])
  def execute(query: String, params: Map[String, Any]): ExecutionResult = prepare(query).execute(params)

  @throws(classOf[SyntaxException])
  def execute(query: String, params: JavaMap[String, Any]): ExecutionResult = execute(query, params.asScala.toMap)

  @throws(classOf[SyntaxException])
  def prepare(query: String): ExecutionPlan = executionPlanCache.getOrElseUpdate(query, new ExecutionPlanImpl(parser.parse(query), graph))

  @throws(classOf[SyntaxException])
  @deprecated(message = "You should not parse queries manually any more. Use the execute(String) instead")
  def execute(query: Query): ExecutionResult = execute(query, Map[String, Any]())

  @throws(classOf[SyntaxException])
  @deprecated(message = "You should not parse queries manually any more. Use the execute(String) instead")
  def execute(query: Query, map: JavaMap[String, Any]): ExecutionResult = execute(query, map.asScala.toMap)

  @throws(classOf[SyntaxException])
<<<<<<< HEAD
  def execute(query: Query, params: Map[String, Any]): ExecutionResult = query match {
    case Query(returns, start, matching, where, aggregation, sort, slice, namedPaths) => {

      val clauses = where match {
        case None => Seq()
        case Some(w) => w.atoms
      }

      val paramPipe = new ParameterPipe(params)
      val pipe = createSourcePumps(paramPipe, start.startItems.toList)
<<<<<<< HEAD

      var context = new CurrentContext(pipe, clauses)
      context = addFilters(context)

      context.pipe = createMatchPipe(matching, namedPaths, context.pipe)
      context = addFilters(context)

      context.pipe = createShortestPathPipe(context.pipe, matching, namedPaths)
      context = addFilters(context)

      namedPaths match {
        case None =>
        case Some(x) => x.paths.foreach(p => context.pipe = new NamedPathPipe(context.pipe, p))
=======

      var context = new CurrentContext(pipe, clauses)
      context = addFilters(context)

      context = createMatchPipe(matching, namedPaths, context)

      context.pipe = createShortestPathPipe(context.pipe, matching, namedPaths)
      context = addFilters(context)

      namedPaths match {
        case None =>
        case Some(x) => x.paths.foreach(p => context.pipe = new NamedPathPipe(context.pipe, p))
      }

      if (context.clauses.nonEmpty) {
        context.pipe = new FilterPipe(context.pipe, context.clauses.reduceLeft(_ ++ _))
>>>>>>> master
      }
      context = addFilters(context)

<<<<<<< HEAD
      val allReturnItems = extractReturnItems(returns, aggregation, sort)
      context = addFilters(context)

      context.pipe = new TransformPipe(context.pipe, allReturnItems)
      context = addFilters(context)
=======
      val allReturnItems = extractReturnItems(returns, aggregation)

      context.pipe = new ExtractPipe(context.pipe, allReturnItems)
>>>>>>> master

      aggregation match {
        case None =>
        case Some(aggr) => {
<<<<<<< HEAD
          context.pipe = new AggregationPipe(context.pipe, returns.returnItems, aggr.aggregationItems)
        }
      }

      sort match {
        case None =>
        case Some(s) => {
          context.pipe = new SortPipe(context.pipe, s.sortItems.toList)
        }
      }
=======
          context.pipe = new EagerAggregationPipe(context.pipe, returns.returnItems, aggr.aggregationItems)
        }
      }

      createSortPipe(sort, allReturnItems, context)
>>>>>>> master

      slice match {
        case None =>
        case Some(x) => context.pipe = new SlicePipe(context.pipe, x.from, x.limit)
      }

      val returnItems = returns.returnItems ++ aggregation.getOrElse(new Aggregation()).aggregationItems

<<<<<<< HEAD
      val result = new ColumnFilterPipe(context.pipe, columns) with ExecutionResult
=======
      val result = new ColumnFilterPipe(context.pipe, returnItems, returns.columns)
>>>>>>> master

      result
    }
  }

  private def createShortestPathPipe(source: Pipe, matching: Option[Match], namedPaths: Option[NamedPaths]): Pipe = {
    val unnamedShortestPaths = matching match {
      case Some(m) => m.patterns.filter(_.isInstanceOf[ShortestPath]).map(_.asInstanceOf[ShortestPath])
      case None => Seq()
    }

    val namedShortestPaths = namedPaths match {
      case Some(m) => m.paths.flatMap(_.pathPattern).filter(_.isInstanceOf[ShortestPath]).map(_.asInstanceOf[ShortestPath])
      case None => Seq()
    }

    val shortestPaths = unnamedShortestPaths ++ namedShortestPaths

    var result = source
    shortestPaths.foreach(p => result = new ShortestPathPipe(result, p))
    result

  }

  private def createMatchPipe(unnamedPaths: Option[Match], namedPaths: Option[NamedPaths], context: CurrentContext): CurrentContext = {
    val namedPattern = namedPaths match {
      case Some(m) => m.paths.flatten
      case None => Seq()
    }

    val unnamedPattern = unnamedPaths match {
      case Some(m) => m.patterns
      case None => Seq()
    }

    (unnamedPattern ++ namedPattern) match {
      case Seq() =>
      case x => context.pipe = new MatchPipe(context.pipe, x, context.clauses)
    }

    context
  }

  private def createSourcePumps(pipe: Pipe, items: List[StartItem]): Pipe = {
    items match {
      case head :: tail => createSourcePumps(createStartPipe(pipe, head), tail)
      case Seq() => pipe
    }
  }

  private def extractReturnItems(returns: Return, aggregation: Option[Aggregation]): Seq[ReturnItem] = {
    val aggregation1 = aggregation.getOrElse(new Aggregation())

    val aggregationItems = aggregation1.aggregationItems.map(_.concreteReturnItem)

    returns.returnItems ++ aggregationItems
  }

  private def createStartPipe(lastPipe: Pipe, item: StartItem): Pipe = item match {
    case NodeByIndex(varName, idxName, key, value) =>
      new NodeStartPipe(lastPipe, varName, m => {
        val keyVal = key(m).toString
        val valueVal = value(m)
        val indexHits: Iterable[Node] = graph.index.forNodes(idxName).get(keyVal, valueVal)
        indexHits.asScala
      })

    case RelationshipByIndex(varName, idxName, key, value) =>
      new RelationshipStartPipe(lastPipe, varName, m => {
        val keyVal = key(m).toString
        val valueVal = value(m)
        val indexHits: Iterable[Relationship] = graph.index.forRelationships(idxName).get(keyVal, valueVal)
        indexHits.asScala
      })

    case NodeByIndexQuery(varName, idxName, query) =>
      new NodeStartPipe(lastPipe, varName, m => {
        val queryText = query(m)
        val indexHits: Iterable[Node] = graph.index.forNodes(idxName).query(queryText)
        indexHits.asScala
      })

    case NodeById(varName, valueGenerator) => new NodeStartPipe(lastPipe, varName, m => makeNodes[Node](valueGenerator(m), varName, graph.getNodeById))
    case RelationshipById(varName, id) => new RelationshipStartPipe(lastPipe, varName, m => makeNodes[Relationship](id(m), varName, graph.getRelationshipById))
  }

  private def addFilters(context: CurrentContext): CurrentContext = {
<<<<<<< HEAD
    if (context.clauses.isEmpty)
      context
=======
    if (context.clauses.isEmpty) {
      context
    }
>>>>>>> master
    else {
      val keys = context.pipe.symbols.identifiers.map(_.name)
      val matchingClauses = context.clauses.filter(x => {
        val unsatisfiedDependencies = x.dependsOn.filterNot(keys contains)
        unsatisfiedDependencies.isEmpty
      })
<<<<<<< HEAD
      if (matchingClauses.isEmpty)
        context
      else {
        val filterClause = matchingClauses.reduceLeft(_ ++ _)
        val p = new FilterPipe(context.pipe, filterClause)

        new CurrentContext(p, context.clauses.filterNot(matchingClauses contains))
      }
    }
  }
=======
      if (matchingClauses.isEmpty) {
        context
      }
      else {
        val filterClause = matchingClauses.reduceLeft(_ ++ _)
        val p = new FilterPipe(context.pipe, filterClause)
>>>>>>> master

        new CurrentContext(p, context.clauses.filterNot(matchingClauses contains))
      }
    }
  }

  private def createSortPipe(sort: Option[Sort], allReturnItems: Seq[ReturnItem], context: CurrentContext) {
    sort match {
      case None =>
      case Some(s) => {

        val sortItems = s.sortItems.map(_.returnItem.concreteReturnItem).filterNot(allReturnItems.contains)
        if (sortItems.nonEmpty) {
          context.pipe = new ExtractPipe(context.pipe, sortItems)
        }
        context.pipe = new SortPipe(context.pipe, s.sortItems.toList)
      }
    }
  }
=======
  @deprecated(message = "You should not parse queries manually any more. Use the execute(String) instead")
  def execute(query: Query, params: Map[String, Any]): ExecutionResult = new ExecutionPlanImpl(query, graph).execute(params)
>>>>>>> master

  private def checkScalaVersion() {
    if (util.Properties.versionString.matches("^version 2.9.0")) {
      throw new Error("Cypher can only run with Scala 2.9.0. It looks like the Scala version is: " +
        util.Properties.versionString)
    }
  }

  private val cacheSize: Int = 100
  private val executionPlanCache = new LRUCache[String, ExecutionPlan](cacheSize) {}
}

