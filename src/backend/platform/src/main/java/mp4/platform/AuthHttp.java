package mp4.platform;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
final class AuthHttp {
    static void error(HttpServletResponse response,int status,String code,String message)throws IOException {
        response.setStatus(status); response.setContentType("application/json; charset=utf-8"); response.setHeader("Cache-Control","no-store");
        response.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+message+"\",\"requestId\":\""+UUID.randomUUID()+"\"}");
    }
    static void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
}
