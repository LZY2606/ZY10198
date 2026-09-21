package app

fun main(args: Array<String>) {
    var port = 8080
    var databasePath = "data/volcano.db"
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--port" -> port = args[++index].toInt()
            "--db" -> databasePath = args[++index]
            else -> throw IllegalArgumentException("未知参数: ${args[index]}")
        }
        index++
    }
    startServer(port, databasePath)
}
