package org.openbot.tracking;

/**
 * 一个可复用的PID控制器实现。
 */
public class PIDCalculator {

    private final double kp; // 比例增益 (Proportional gain)
    private final double ki; // 积分增益 (Integral gain)
    private final double kd; // 微分增益 (Derivative gain)

    private double integral = 0;   // 积分项累加值
    private double lastError = 0;  // 上一次的误差，用于计算微分项

    /**
     * 构造函数
     * @param kp 比例增益
     * @param ki 积分增益
     * @param kd 微分增益
     */
    public PIDCalculator(double kp, double ki, double kd) {
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
    }

    /**
     * 计算PID控制器的输出。
     * @param error 当前误差 (Setpoint - CurrentValue)
     * @param dt    距离上次计算的时间差（单位：秒）
     * @return 控制器的输出值
     */
    public double calculate(double error, double dt) {
        // P (比例项): 直接反应当前误差
        double p_out = kp * error;

        // I (积分项): 累加误差，用于消除稳态误差
        // 注意：为防止积分饱和(integral windup), 在实际项目中可能需要对integral值进行限制
        integral += error * dt;
        double i_out = ki * integral;

        // D (微分项): 反应误差变化率，用于抑制震荡，提供预见性
        double derivative = (dt >= 0.0001) ? (error - lastError) / dt : 0;
        double d_out = kd * derivative;

        // 更新上一次误差
        lastError = error;

        // 计算总输出
        return p_out + i_out + d_out;
    }

    /**
     * 重置控制器状态，在需要时（如目标切换）调用。
     */
    public void reset() {
        this.integral = 0;
        this.lastError = 0;
    }
}
