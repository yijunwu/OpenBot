package org.openbot.tracking;

import org.openbot.vehicle.Control;

public class RobotController {
    // --- 物理和目标参数 ---
    final double WHEEL_BASE = 0.14;     // 您的车宽，单位：米
    final double MAX_SPEED = 1.0;       // 电机最大速度，单位：米/秒
    final double TARGET_DISTANCE = 0.7; // 0.4 // 期望保持的距离，单位：米

    // --- PID 增益参数 (这些是调优的关键，需要大量实验!) ---
    // 建议的调优顺序:
    // 1. 先设置 Ki 和 Kd 为 0，只调整 Kp 得到一个大致可用的效果 (可能会震荡)。
    // 2. 逐渐增加 Kd 来抑制震荡，使动作平滑。
    // 3. 如果有稳态误差（总是差一点才到目标），再慢慢增加 Ki 来消除它。

    // 车辆距离PID增益
    final double KP_D = 0.6; //0.4; //0.8;
    final double KI_D = 0.0;
    final double KD_D = 0.06; //0.0;

    // 车辆角度PID增益
    final double KP_A = 8; //10; //1.25;
    final double KI_A = 0.0;
    final double KD_A = 0.8; //0.0;

    // 舵机角度PID增益
    final double KP_SA = 0.8; //0.20; //0.25;
    final double KI_SA = 0.0;
    final double KD_SA = 0.08; //0.06; //0.05;

    // 创建机器人控制器实例
    PIDController robot = new PIDController(
            WHEEL_BASE, MAX_SPEED, TARGET_DISTANCE,
            KP_D, KI_D, KD_D,
            KP_A, KI_A, KD_A,
            KP_SA, KI_SA, KD_SA
    );

    public Control update(TargetInfo target, Control lastControl) {
        return robot.update(target, lastControl);
    }
}
