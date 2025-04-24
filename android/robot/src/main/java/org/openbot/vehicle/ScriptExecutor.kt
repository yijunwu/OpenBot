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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign


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
            } else if (cmd.startsWith("delay")) {
                val m = Pattern.compile("delay[ ]*$paramList").matcher(cmd)
                m.find()
                val args = m.group(1).split(",\\s*".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
                delay(args[0].replace("ms", "").trim().toLong())
            }
        } catch (e: Exception) {
            System.err.println("执行命令失败: $cmd $e")
        }
    }


}

// 假设存在的机器人操作方法
internal object Robot {
    val rightFactor: Double = 1.66
    val leftFactor: Double = 1.05 * rightFactor
    val backwardFactor: Double = 1.1
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
        val width: Double = 0.13 //左右轮中心距
        val normalizedRotateDirection = rotateDirection?.replace("-", "")?.lowercase()
        val sign1 = if ("counterclockwise" == normalizedRotateDirection) 1 else -1
        val sign2 = if ("forward" == headDirection) 1 else -1
        //val t: Long = (((radius + width / 2) * PI * angle / 180 / (speed * factor)) * 1000.00).roundToLong()

        //val t: Long = (angle / speed * 1000.00).roundToLong()
        val adjustedWidth = width * (sign1 * sign2)

        var leftSpeed: Double = speed / 180.0 * PI * (radius - adjustedWidth / 2)
        var rightSpeed: Double = speed / 180.0 * PI * (radius + adjustedWidth / 2)

        val maxSpeed = 1.11
        val controlValueForMaxSpeed = 1.4
        var actualLeft: Double
        var actualRight: Double
        val t: Long
        // 如果速度超出最大速度，则等比例调整左右轮速度（保持半径不变），同时延长动作时间
        if (max(abs(leftSpeed), abs(rightSpeed)) > maxSpeed) {
            actualLeft = leftSpeed / max(abs(leftSpeed), abs(rightSpeed)) * maxSpeed
            actualRight = rightSpeed / max(abs(leftSpeed), abs(rightSpeed)) * maxSpeed
            t = ((angle / speed) * (max(abs(leftSpeed), abs(rightSpeed)) / maxSpeed) * 1000).toLong()
        } else {
            actualLeft = leftSpeed
            actualRight = rightSpeed
            t = (angle / speed * 1000).toLong()
        }
        //低速负载补偿
        val diffRatio = (actualRight - actualLeft).pow(2) / (actualRight.pow(2) + actualLeft.pow(2)) / 2
        actualLeft = compensate4(actualLeft, diffRatio)
        actualRight = compensate4(actualRight, diffRatio)
        val leftSpeedAndControl = speedToControl(actualLeft)
        val rightSpeedAndControl = speedToControl(actualRight)

        vehicle.setControl(leftSpeedAndControl.second * sign2, rightSpeedAndControl.second * sign2)
        delay(t)
        //delay(t + 100)
        vehicle.setControl(0.0F, 0.0F)
        Log.i("ScriptExecutor",
            "执行旋转：角度=$angle° 半径=${radius}m 转动方向=$rotateDirection 车头朝向=$headDirection 速度=${speed}d/s",
        )
        Log.i("ScriptExecutor",
            "执行旋转：左轮速度=${actualLeft * sign2}, 右轮速度=${actualRight * sign2}, 持续时间=${t}ms",
        )
    }

    /**
     * diffRatio: 0 到 2
     */
    private fun compensate2(speed: Double, diffRatio: Double): Double {
        val absSpeed = abs(speed)
        val diffSignificance = 0
        val slowSignicicance = 1
        return when {
            // TODO wuyijun 简单实现，待优化（插值）
            absSpeed < 0.1 -> ((5.0 - 1).pow(2) * slowSignicicance / 4 / (diffSignificance + 1) + 1) * speed
            absSpeed < 0.2 -> ((3.5 - 1) * slowSignicicance / 4 / (diffSignificance + 1) + 1) * speed
            absSpeed < 0.3 -> ((2.2 - 1) * slowSignicicance / 4 / (diffSignificance + 1) + 1) * speed
            absSpeed < 0.4 -> ((1.9 - 1) * slowSignicicance / 4 / (diffSignificance + 1) + 1) * speed
            absSpeed < 0.5 -> ((1.7 - 1) * slowSignicicance / 4 / (diffSignificance + 1) + 1) * speed
            absSpeed < 0.6 -> ((1.5 - 1) * slowSignicicance / 4 / (diffSignificance + 1) + 1) * speed
            absSpeed < 0.7 -> ((1.4 - 1) * slowSignicicance / 4 / (diffSignificance + 1) + 1) * speed
            absSpeed < 0.8 -> ((1.2 - 1) * slowSignicicance / 4 / (diffSignificance + 1) + 1) * speed
            absSpeed < 0.9 -> ((1.08 - 1) * slowSignicicance / 4 / (diffSignificance + 1) + 1) * speed
            absSpeed < 1.0 -> ((1.05 - 1) * slowSignicicance / 4 / (diffSignificance + 1) + 1) * speed
            absSpeed < 1.1 -> ((1.01 - 1) * slowSignicicance / 4 / (diffSignificance + 1) + 1) * speed
            else -> 1.0 * speed
        } * (1 + diffRatio * diffSignificance)
    }

    private fun compensate3(speed: Double, diffRatio: Double): Double {
        val maxSpeed = 1.11
        val diffSignificance = 1
        val p: Double = 1.5

        val s1 = maxSpeed * (abs(speed) / maxSpeed).pow(1/p) * sign(speed)
        val s2 = s1 * (1 + diffRatio * diffSignificance) // / (diffSignificance + 1)
        return s2
    }

    private fun compensate4(speed: Double, diffRatio: Double): Double {
        val maxSpeed = 1.11
        val diffSignificance = 1
        val p: Double = 1.5

        val s1 = maxSpeed * (abs(speed) / maxSpeed).pow(1/p) * sign(speed)
        val s2 = s1 * (1 + diffRatio * diffSignificance) // / (diffSignificance + 1)
        return s2
    }

    suspend fun straight(vehicle: Vehicle, distance: Double, headDirection: String?, speed: Double) {
        val sign = if ("backward".equals(headDirection, ignoreCase = true)) -1 else 1

        val (actualSpeed: Float, actual) = speedToControl(compensate(speed, 0.0))
        val t: Long = ((distance / actualSpeed) * 1000.0).roundToLong()
        vehicle.setControl(actual * sign, actual * sign)
        delay(t)
        vehicle.setControl(0.0F, 0.0F)

        Log.i("ScriptExecutor",
            "执行直行：距离=${distance}m 方向=$headDirection 速度=${speed}m/s"
        )
        Log.i("ScriptExecutor",
            "执行直行：左轮速度=${actual * sign}, 右轮速度=${actual * sign}, 持续时间=${t}ms",
        )
    }

    private fun speedToControl(speed: Double): Pair<Float, Float> {
        val speedAbs = if (speed < 0) -speed else speed
        val maxSpeedInMetersPerSec = 1.11 // 当传255给到单片机时车子能达到的速度，米/秒
        val controlValueForMaxSpeed = 1.0
        val controlValueForZeroSpeed = 0.2
        val range = controlValueForMaxSpeed - controlValueForZeroSpeed
        val actualSpeed: Float = min(speedAbs, maxSpeedInMetersPerSec).toFloat()

        val actual =
            (actualSpeed / maxSpeedInMetersPerSec * range + controlValueForZeroSpeed).toFloat()
        return Pair(actualSpeed * sign(speed).toFloat(), actual * sign(speed).toFloat())
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