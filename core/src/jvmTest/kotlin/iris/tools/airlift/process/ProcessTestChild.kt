package iris.tools.airlift.process

/** A real child JVM avoids reliance on shell quoting, Unix tools or network access in process tests. */
object ProcessTestChild {
    @JvmStatic fun main(args: Array<String>) {
        when (args[0]) {
            "echo" -> System.`in`.copyTo(System.out)
            "separate" -> { System.out.print("out"); System.out.flush(); System.err.print("err"); System.err.flush() }
            "inspect" -> System.out.print("${args[1]}\n${System.getenv("AIRLIFT_TEST")}\n${System.getProperty("user.dir")}")
            "exit" -> kotlin.system.exitProcess(23)
            "block" -> Thread.sleep(60_000)
            "duplex" -> { System.out.write(ByteArray(512 * 1024) { 42 }); System.out.flush(); System.`in`.copyTo(System.out) }
        }
    }
}
