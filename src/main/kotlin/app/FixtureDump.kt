package app

/**
 * 生成固定演示 fixture 的标准 CSV（用于清空数据库后重新导入复核）。
 * 用法：mvn exec:java -Dexec.mainClass=app.FixtureDumpKt
 *   或直接重定向：... > fixtures/volcano-a.csv
 */
fun main() {
    print(CsvImport.render(Fixture.bundle()))
}
