package org.openbot.tracking;

import org.openbot.vehicle.Control;

public class PController {

    // --- 物理参数 (final表示这些值在对象创建后不会改变) ---
    private final double wheelBase; // 轮距 (米), L
    private final double maxSpeed;  // 电机的最大速度 (例如: m/s 或 rad/s)

    // --- 控制参数 (这些值需要反复试验来调整) ---
    private final double kpAngle;
    private final double kpDistance;
    private final double targetDistance;

    /**
     * 构造函数
     * @param wheelBase 机器人轮距 (米)
     * @param maxSpeed 电机最大速度
     * @param kpAngle 角度比例增益
     * @param kpDistance 距离比例增益
     * @param targetDistance 目标距离 (米)
     */
    public PController(double wheelBase, double maxSpeed, double kpAngle, double kpDistance, double targetDistance) {
        this.wheelBase = wheelBase;
        this.maxSpeed = maxSpeed;
        this.kpAngle = kpAngle;
        this.kpDistance = kpDistance;
        this.targetDistance = targetDistance;
    }

    public Control update(TargetInfo target) {
        Control controlWheels = updateWheels(target);
        float servoAngle = updateServo(target);
        return new Control(controlWheels.getLeft(), controlWheels.getRight(), servoAngle);
    }

    public float updateServo(TargetInfo target) {
        return 0.0f;
    }

    /**
     * 执行单次控制循环的更新
     */
    public Control updateWheels(TargetInfo target) {
        // 1. 获取传感器数据
        double currentDistance = target.getDistance();
        double currentAngle = target.getAngle(); // 假设角度单位是弧度

        // 2. 计算误差
        double errorDistance = currentDistance - this.targetDistance;
        double errorAngle = currentAngle; // 目标角度是0, 所以误差就是当前角度

        // 3. 计算期望的线速度和角速度 (P控制)
        double v = this.kpDistance * errorDistance; // 期望线速度
        double omega = this.kpAngle * errorAngle;   // 期望角速度

        // 4. 逆向运动学解算 -> 得到左右轮速度
        double v_R = v + (omega * this.wheelBase) / 2.0;
        double v_L = v - (omega * this.wheelBase) / 2.0;

        // 5. 速度限制 (重要!)
        v_R = Math.max(-this.maxSpeed, Math.min(v_R, this.maxSpeed));
        v_L = Math.max(-this.maxSpeed, Math.min(v_L, this.maxSpeed));

        // 6. 发送指令给电机
        return new Control((float)v_L, (float)v_R, 0.0f);
    }
}
