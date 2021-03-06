  /***************************************************************************
   * store graph data that are relevant to community detection
   * which involves a few scalar (Double or Long) variables
   * and a GraphFrame
   * importantly, the GraphFrame object stores reduced graph
   * where each node represents a module/community
   * this reduced graph can be combined with the original graph
   * given a partitioning mapping each nodal index to a modular index
   ***************************************************************************/

import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.graphframes._

sealed case class Network
(
  tele: Double, // PageRank teleportation chance
  nodeNumber: Long, // number of vertices/nodes in network
  // graph: GraphFrame:
  // vertices: modular properties
  // vertices: | id , size , prob , exitw , exitq |
  // (module index) (ergidc frequency) (exit prob w/o tele) (exit prob w/ tele)
  // edges: transition probability w/o tele
  // edges: | src , dst , exitw |
  graph: GraphFrame,
  // sum_node plogp(prob), for codelength calculation
  // it can only be calculated with the full graph and not the reduced one
  // therefore it is calculated during Network.init()
  // and stored here
  probSum: Double,
  codelength: Double // information theoretic entropy
)

object Network
{
  /***************************************************************************
   * given a graph/GraphFrame (probably from GraphFile.graph)
   * and the PageRank teleportation probability
   * calculate PageRank and exit probabilities for each node
   * these are put and returned to a Network object
   * which can be used for community detection
   ***************************************************************************/
  def init( graph0: GraphFrame, tele: Double ): Network = {
    // graph.vertices: ( id: Long, name: String, module: Long )
    // graph.edges: ( src: Long, dst: Long, exitw: Double )

    val graph1 = normalizeEdges( aggregateEdges( trimSelfEdge(graph0) ) )
    // val force = graph0.vertices.count
    // val force2 = graph0.vertices.head.getLong(0)
    // graph0.vertices.cache
    // val force3 = graph0.vertices.head.getLong(0)
    graph1.cache
    val nodeNumber: Long = {
      val count = graph1.vertices.groupBy().count
      count.cache
      count.rdd.count
      count.head.getLong(0)
    }

    // get PageRank ergodic frequency for each node
    val probUnnormalized = graph1.pageRank.resetProbability(tele).tol(0.01).run
    probUnnormalized.vertices.cache

    // normalize page rank
    val probNorm = {
      val sum = probUnnormalized.vertices.select(
        col("id"), col("pagerank")
      )
      .groupBy().sum("pagerank")
      sum.rdd.count
      sum.cache
      sum.head.getDouble(0)
    }
    val prob = probUnnormalized.vertices.select(
      col("id"), col("pagerank")/lit(probNorm) as "prob"
    )
    prob.cache

    // modular information
    // since transition probability is normalized per 'src node,
    // w and q are mathematically identical to p
    // as long as there is at least one connection
    // | id , size , prob , exitw , exitq |
    val modules = {
      val nodeCount = graph1.vertices.groupBy().count.head.getLong(0)
      prob.join( graph1.edges.select(col("src")).distinct,
        col("id") === col("src"), "left_outer"
      )
      .select(
        col("id"),
        lit(1) as "size",
        col("prob"),
        when( col("src").isNotNull, col("prob") )
        .otherwise(lit(0)) as "exitw",
        when( lit(nodeCount)===lit(1), 0 )
        .when( col("src").isNotNull, col("prob") )
        .otherwise( lit(tele) *col("prob") ) as "exitq"
      )
    }
    modules.cache

    // probability of transitioning within two modules w/o teleporting
    // | src , dst , exitw |
    val edges = prob.join(
      graph1.edges.filter( "src != dst" ), // filter away self connections
      col("id") === col("src")
    )
    .select(
      col("src"),
      col("dst"),
      col("prob")*col("exitw") as "exitw"
    )
    edges.cache

    // calculate current code length
    val probSum = {
      val sum = modules.select(
        CommunityDetection.plogp()( col("prob") ) as "plogp_p"
      )
      .groupBy().sum("plogp_p")
      sum.rdd.count
      sum.cache
      sum.head.getDouble(0)
    }

    val codelength = CommunityDetection.calCodelength( modules, probSum )

    Network(
      tele, nodeNumber,
      GraphFrame(modules,edges),
      probSum,
      codelength
    )
  }

  // remove edges where the src and dst vertices are identical
  def trimSelfEdge( graph: GraphFrame ): GraphFrame = {
    GraphFrame( graph.vertices,
      graph.edges.filter("src != dst")
    )
  }

  // remove edges where the src and dst vertices are identical
  def aggregateEdges( graph: GraphFrame ): GraphFrame = {
    GraphFrame( graph.vertices,
      graph.edges.groupBy("src","dst").sum("exitw")
        .select( col("src"), col("dst"), col("sum(exitw)") as "exitw" )
    )
  }

  // normalize the edge weights of the graph
  // with respect to the src vertex
  def normalizeEdges( graph: GraphFrame ): GraphFrame = {
    GraphFrame( graph.vertices,
      graph.edges
      .groupBy("src").sum("exitw")
      .join( graph.edges, "src" )
      .select(
        col("src"), col("dst"),
        col("exitw")/col("sum(exitw)") as "exitw"
      )
    )
  }

  // function to trim RDD/DF lineage
  // which should be performed within community detection algorithm iterations
  // to avoid stack overflow problem
  def trim( df: DataFrame ): Unit = {
    df.cache
    df.rdd.localCheckpoint
    val count = df.rdd.count
    val spark: SparkSession =
    SparkSession
      .builder()
      .appName("InfoFlow")
      .config("spark.master", "local[*]")
      .getOrCreate()
    import spark.implicits._
    df.rdd.toDF
  }
}
