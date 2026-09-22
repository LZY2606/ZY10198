package app

import java.io.File

fun main(args: Array<String>) {
    var port = 8080
    var dbPath = "data/volcano-studio.sqlite"
    var autoFixture = true
    var webRoot: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args[++i].toInt() }
            "--db" -> { dbPath = args[++i] }
            "--no-fixture" -> autoFixture = false
            "--web-root" -> webRoot = args[++i]
            "--help", "-h" -> {
                println("用法: app.MainKt [--port 5538] [--db data/x.sqlite] [--no-fixture] [--web-root DIR]")
                return
            }
            else -> throw IllegalArgumentException("未知参数: ${args[i]}")
        }
        i++
    }
    val repo = Repository(Database(dbPath))
    if (autoFixture && repo.listDatasets().isEmpty()) {
        repo.importBundle(Fixture.bundle())
        println("已自动导入固定演示 fixture（数据集 1）")
    }
    println("火山形变解译台 启动于 http://127.0.0.1:$port  （db=$dbPath）")
    WebServer(repo, port, webRoot?.let { File(it) }).start()
}
