package io.chymyst.test

import io.chymyst.jc._
import io.chymyst.test.Common._

class FairnessSpec extends LogSpec {

  behavior of "reaction site"

  // fairness over reactions:
  // We have n molecules A:M[Unit], which can all interact with a single molecule C:M[(Int,Array[Int])].
  // We first emit all A's and then a single C.
  // Each molecule A_i will increment C's counter at index i upon reaction.
  // We repeat this for N iterations, then we read the array and check that its values are distributed more or less randomly.

  it should "implement fairness across reactions" in {
    (1 to 10).map { _ =>
      val reactions = 4
      val N = 200

      val c = m[(Int, Array[Int])]
      val done = m[Array[Int]]
      val getC = b[Unit, Array[Int]]
      val a0 = m[Unit]
      val a1 = m[Unit]
      val a2 = m[Unit]
      val a3 = m[Unit]
      //n = 4

      val tp = FixedPool(4)

      site(tp)(
        go { case getC(_, r) + done(arr) => r(arr) },
        go {
          case a0(_) + c((n, arr)) => if (n > 0) {
            arr(0) += 1
            c((n - 1, arr))
            Thread.sleep(10)
            a0()
          } else done(arr)
        },
        go {
          case a1(_) + c((n, arr)) => if (n > 0) {
            arr(1) += 1
            c((n - 1, arr))
            Thread.sleep(10)
            a1()
          } else done(arr)
        },
        go {
          case a2(_) + c((n, arr)) => if (n > 0) {
            arr(2) += 1
            c((n - 1, arr))
            Thread.sleep(10)
            a2()
          } else done(arr)
        },
        go {
          case a3(_) + c((n, arr)) => if (n > 0) {
            arr(3) += 1
            c((n - 1, arr))
            Thread.sleep(10)
            a3()
          } else done(arr)
        }
      )

      a0() + a1() + a2() + a3()
      Thread.sleep(10)
      c((N, Array.fill[Int](reactions)(0)))

      val result = getC()
      tp.shutdownNow()

      val average = N / reactions
      val max_deviation = math.max(math.abs(result.min - average).toDouble, math.abs(result.max - average).toDouble) / average
      println(s"Fairness across 4 reactions: ${result.mkString(", ")}. Average = $average. Max relative deviation = $max_deviation")
      max_deviation
    }.min should be < 0.2
  }

  // fairness across molecules: will be automatic here since all molecules are pipelined.
  // Emit n molecules A[Int] that can all interact with C[Int]. Each time they interact, their counter is incremented.
  // Then emit a single C molecule, which will react until its counter goes to 0.
  // At this point, gather all results from A[Int] into an array and return that array.

  it should "implement fairness across molecules" in {

    val counters = 20

    val cycles = 10000

    val c = m[Int]
    val done = m[List[Int]]
    val getC = b[Unit, List[Int]]
    val gather = m[List[Int]]
    val a = m[Int]

    val tp = FixedPool(8)

    site(tp)(
      go { case done(arr) + getC(_, r) => r(arr) },
      go { case c(n) + a(i) if n > 0 => a(i + 1) + c(n - 1) },
      go { case c(0) + a(i) => a(i) + gather(List()) },
      go { case gather(arr) + a(i) =>
        val newArr = i :: arr
        if (newArr.size < counters) gather(newArr) else done(newArr)
      }
    )

    (1 to counters).foreach(_ => a(0))
    Thread.sleep(100)
    c(cycles)

    val result = getC()
    println(result.mkString(", "))

    tp.shutdownNow()

    result.min.toDouble should be > (cycles / counters * 0.8)
    result.max.toDouble should be < (cycles / counters * 1.2)
  }

  behavior of "multiple emission"

  /** Emit an equal number of a, bb, c molecules. One reaction consumes a + bb and the other consumes bb + c.
    * Verify that both reactions proceed with probability roughly 1/2.
    */
  it should "schedule reactions fairly after multiple emission" in {
    val a = m[Unit]
    val bb = m[Unit]
    val c = m[Unit]
    val d = m[Unit]
    val e = m[Unit]
    val f = m[(Int, Int, Int)]
    val g = b[Unit, (Int, Int)]

    val tp = FixedPool(8)

    site(tp)(
      go { case a(x) + bb(y) if x == y => d() },
      go { case bb(x) + c(y) if x == y => e() },
      go { case d(_) + f((x, y, t)) => f((x + 1, y, t - 1)) },
      go { case e(_) + f((x, y, t)) => f((x, y + 1, t - 1)) },
      go { case g(_, r) + f((x, y, 0)) => r((x, y)) }
    )
    val total = 200
    val results = (1 to total).map { _ ⇒
      val n = 50

      f((0, 0, n))
      (1 to n).foreach { _ =>
        if (scala.util.Random.nextInt(2) == 0)
          a() + c() + bb()
        else c() + a() + bb()
      }

      val (ab, bc) = g()
      ab + bc shouldEqual n
      val discrepancy = math.abs(ab - bc + 0.0) / n
//      println(s"Reaction a + bb occurred $ab times. Reaction bb + c occurred $bc times. Discrepancy $discrepancy")
      (ab.toDouble / n, discrepancy)
    }
    val averageAB = results.map(_._1).sum / total
    val minDiscrepancy = results.map(_._2).min
    println(s"Average occurrence of a + bb is $averageAB. Min. discrepancy = $minDiscrepancy")
    averageAB should be > 0.3
    averageAB should be < 0.7

    tp.shutdownNow()
  }

  // This test failed to complete in 500ms on Travis CI with Scala 2.10, but succeeds with 2.11. However, this could have been a fluctuation.
  it should "fail to schedule reactions fairly after multiple emission into separate RSs" in {

    val tp = FixedPool(8)

    def makeRS(d1: M[Unit], d2: M[Unit]): (M[Unit], M[Unit], M[Unit]) = {
      val a = m[Unit]
      val b = m[Unit]
      val c = m[Unit]

      site(tp)(
        go { case a(_) + b(_) => d1() },
        go { case b(_) + c(_) => d2() }
      )
      (a, b, c)
    }

    val d = m[Unit]
    val e = m[Unit]
    val f = m[(Int, Int, Int)]
    val g = b[Unit, (Int, Int)]

    site(tp)(
      go { case d(_) + f((x, y, t)) => f((x + 1, y, t - 1)) },
      go { case e(_) + f((x, y, t)) => f((x, y + 1, t - 1)) },
      go { case g(_, r) + f((x, y, 0)) => r((x, y)) }
    )

    val n = 400

    f((0, 0, n))

    repeat(n) {
      val (a, b, c) = makeRS(d, e)
      a() + b() + c() // at the moment, this is equivalent to a(); b(); c.
      // this test will need to be changed when true multiple emission is implemented.
    }

    val (ab, bc) = g()
    ab + bc shouldEqual n
    val discrepancy = math.abs(ab - bc + 0.0) / n
    discrepancy should be > 0.4

    tp.shutdownNow()
  }

}
