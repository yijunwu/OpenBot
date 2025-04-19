package org.openbot.vehicle

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.regex.Pattern
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.future.future
import java.util.concurrent.Executors


class ScriptExecutor(val vehicle: Vehicle) {
    val scriptExecutor = Executors.newSingleThreadExecutor { runnable ->
        object : Thread(runnable, "AudioThread") {
            override fun run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                super.run()
            }
        }
    }
    val scriptDispatcher = scriptExecutor.asCoroutineDispatcher()

    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun executeScript(script: String) {
        val commands = preprocess(script)
        processCommands(commands, 0, commands.size)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun executeScriptAsync(script: String): CompletableFuture<Void?> {
        return CoroutineScope(scriptDispatcher).future {
            executeScript(script)
            null
        }
    }

    private fun preprocess(script: String): List<String> {
        val filtered: MutableList<String> = ArrayList()
        for (line in script.split('\n').dropLastWhile { it.isEmpty() }.toTypedArray()) {
            var trimmed = line.trim { it <= ' ' }
                .replace("//.*".toRegex(), "") // 移除行内注释
                .replace(" <speech>.*</speech>".toRegex(), "") // 移除可能混入到Action中的speech
                .replace("^[0-9]+[\\. ]{0,1}".toRegex(), "") // 移除开头可能有的代码行号
                .replace("^[| ]+".toRegex(), "") // 移除开头可能有的竖线制表符
                .trim { it <= ' ' }
            if (trimmed.isNotEmpty()) {
                filtered.add(trimmed)
            }
        }
        return filtered
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun processCommands(commands: List<String>, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            val cmd = commands[i]

            if (cmd.startsWith("repeat")) {
                handleRepeat(commands, i, end)
                i = findMatchingBrace(commands, i, end) + 1
            } else {
                executeCommand(cmd)
                i++
            }
        }
        return i
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun handleRepeat(commands: List<String>, start: Int, end: Int) {
        val m = Pattern.compile("repeat[ ]*\\((\\d+)\\)").matcher(commands[start])
        if (!m.find()) return

        val times = m.group(1)?.toInt() ?: 1
        val repeatEnd = findMatchingBrace(commands, start, end)

//        val loopBody: MutableList<String> = ArrayList()
//        for (i in start + 1 until end) {
//            loopBody.add(commands[i])
//        }

        for (i in 0 until times) {
            processCommands(commands, start + 1, repeatEnd)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun findMatchingBrace(commands: List<String>, start: Int, end: Int): Int {
        var braceCount = 0
        for (i in start until min(commands.size, end)) {
            val line = commands[i]

            braceCount += line.chars().filter { c: Int -> c == '{'.code }.count().toInt()
            braceCount -= line.chars().filter { c: Int -> c == '}'.code }.count().toInt()

            if (braceCount == 0) return i
            if (braceCount < 0) throw RuntimeException("Unmatched brace for line $start until line $end")
        }
        throw RuntimeException("Unclosed brace for line $start until line $end")
    }

    private suspend fun executeCommand(cmd: String) {
        try {
            val paramList = "\\(([a-zA-Z0-9 \\-,./]+)\\)"
            if (cmd.startsWith("rotate")) {
                val m = Pattern.compile("rotate[ ]*$paramList").matcher(cmd)
                m.find()
                val args = m.group(1).split(",\\s*".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
                Robot.rotate(
                    vehicle,
                    args[0].toInt(),
                    args[1].replace("m","").toDouble(),
                    args[2],
                    args[3],
                    args[4].replace("degree/s", "")
                        .replace("m/s", "").toDouble()
                )
            } else if (cmd.startsWith("straight")) {
                val m = Pattern.compile("straight[ ]*$paramList").matcher(cmd)
                m.find()
                val args = m.group(1).split(",\\s*".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
                Robot.straight(
                    vehicle,
                    args[0].replace("m","").toDouble(),
                    args[1],
                    args[2].replace("m/s", "").toDouble()
                )
            }
        } catch (e: Exception) {
            System.err.println("执行命令失败: $cmd $e")
        }
    }


}

// 假设存在的机器人操作方法
internal object Robot {
    /**
     * angle: 单位度
     * radius: 单位米
     * rotateDirection: clockwise, counter-clockwise
     * headDirection: forward, backward
     * speed: 单位 度/秒
     */
    suspend fun rotate(
        vehicle: Vehicle, angle: Int, radius: Double,
        rotateDirection: String?, headDirection: String?, speed: Double
    ) {
        //(radius + 车宽/2) * pi * angle / 180 = speed * t
        //(leftSpeed + rightSpeed) / 2 = speed
        //rightSpeed / leftSpeed = (radius + 1/2 车宽) / (radius + 1/2 车宽)
        val factor: Double = 1.4
        val width: Double = 0.2
        val normalizedRotateDirection = rotateDirection?.replace("-", "")?.lowercase()
        val temp1 = if ("counterclockwise" == normalizedRotateDirection) 1 else -1
        val temp2 = if ("forward" == headDirection) 1 else -1
        //val t: Long = (((radius + width / 2) * PI * angle / 180 / (speed * factor)) * 1000.00).roundToLong()
        val t: Long = (angle / speed * 1000.00).roundToLong()
        val adjustedWidth = width * (temp1 * temp2)
        //val leftSpeed: Double = speed * factor * (radius - adjustedWidth / 2)
        val leftSpeed: Double = speed * factor / 180 * PI * (radius - adjustedWidth / 2)
        //val rightSpeed: Double = speed * factor * (radius + adjustedWidth / 2)
        val rightSpeed: Double = speed * factor / 180 * PI * (radius + adjustedWidth / 2)
        val actualLeft = (leftSpeed * temp2).toFloat()
        val actualRight = (rightSpeed * temp2).toFloat()
        vehicle.setControl(actualLeft, actualRight)
        delay(t)
        vehicle.setControl(0.0F, 0.0F)
        Log.i("ScriptExecutor",
            "执行旋转：角度=$angle° 半径=${radius}m 转动方向=$rotateDirection 车头朝向=$headDirection 速度=${speed}d/s",
        )
        Log.i("ScriptExecutor",
            "执行旋转：左轮速度=$actualLeft, 右轮速度=$actualRight, 持续时间=${t}ms",
        )
    }

    suspend fun straight(vehicle: Vehicle, distance: Double, headDirection: String?, speed: Double) {
        val t: Long = ((distance / speed) * 1000.0).roundToLong()
        val sign = if ("backward".equals(headDirection, ignoreCase = true)) -1 else 1
        val factor: Double = 1.4
        val actualSpeed = (speed * factor * sign).toFloat()
        vehicle.setControl(actualSpeed, actualSpeed)
        delay(t)
        vehicle.setControl(0.0F, 0.0F)

        Log.i("ScriptExecutor",
            "执行直行：距离=${distance}m 方向=$headDirection 速度=${speed}m/s"
        )
        Log.i("ScriptExecutor",
            "执行直行：左轮速度=$actualSpeed, 右轮速度=$actualSpeed, 持续时间=${t}ms",
        )
    }
}

class ScriptExecutor2 {
    fun execute(vehicle: Vehicle, script: String) {
        CoroutineScope(Dispatchers.IO).launch {
            vehicle.setControl(0.2f, 1f)
            delay(2000)
            vehicle.setControl(1f, 0.2f)
            delay(2000)

            vehicle.setControl(0.2f, 1f)
            delay(2000)
            vehicle.setControl(1f, 0.2f)
            delay(2000)

            vehicle.setControl(0.2f, 1f)
            delay(2000)
            vehicle.setControl(1f, 0.2f)
            delay(2000)

            vehicle.setControl(0.2f, 1f)
            delay(2000)
            vehicle.setControl(1f, 0.2f)
            delay(2000)

            vehicle.setControl(-1f, -1f)
            delay(1000)
            vehicle.setControl(2f, 2f)
            delay(1000)
            vehicle.setControl(0f, 0f)
        }
    }
}