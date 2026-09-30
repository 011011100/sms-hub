import com.smshub.app.Otp;

public class OtpTest {
    static void code(String body, String expected) {
        String actual = Otp.extract(body);
        if (!expected.equals(actual)) throw new AssertionError(body + " expected=" + expected + " actual=" + actual);
    }
    public static void main(String[] args) {
        code("【测试】您的验证码是 123456，5 分钟内有效。", "123456");
        code("Your verification code is 938104. Expires in 10 minutes.", "938104");
        code("482913 is your verification code.", "482913");
        code("安全码：A1B2C3，请勿泄露", "A1B2C3");
        code("验证码是 038291。手机号 13800138000。", "038291");
        code("验证码已发送至 13800138000", "");
        code("verification code", "");
        if (Otp.isVerification("今晚六点见")) throw new AssertionError("Private conversation matched");
        if (!Otp.isVerification("验证码暂时不可用")) throw new AssertionError("Keyword not detected");
        System.out.println("OTP extraction checks passed");
    }
}
