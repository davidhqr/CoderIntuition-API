package com.coderintuition.CoderIntuition.common;

import com.coderintuition.CoderIntuition.enums.Language;
import com.coderintuition.CoderIntuition.pojos.request.JZSubmissionRequestDto;
import com.coderintuition.CoderIntuition.pojos.response.JZSubmissionResponse;
import com.coderintuition.CoderIntuition.pojos.response.JzSubmissionCheckResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.sargue.mailgun.Configuration;
import net.sargue.mailgun.Mail;
import net.sargue.mailgun.Response;
import okhttp3.*;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.Base64Utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Random;

public class Utils {
    public static int getLanguageId(Language language) {
        if (language == Language.PYTHON) {
            return 71;
        } else if (language == Language.JAVA) {
            return 62;
        } else if (language == Language.JAVASCRIPT) {
            return 63;
        }
        return -1;
    }

    public static String getFunctionName(Language language, String defaultCode) {
        switch (language) {
            case PYTHON:
                int startIndex = defaultCode.indexOf("def ") + 4;
                int endIndex = defaultCode.indexOf("(", startIndex);
                return defaultCode.substring(startIndex, endIndex);
            case JAVA:
                endIndex = defaultCode.indexOf("(");
                int cur = endIndex - 1;
                while (defaultCode.charAt(cur) != ' ') {
                    cur--;
                }
                return defaultCode.substring(cur + 1, endIndex);
            case JAVASCRIPT:
                startIndex = defaultCode.indexOf("function ") + 9;
                endIndex = defaultCode.indexOf("(", startIndex);
                return defaultCode.substring(startIndex, endIndex);
            default:
                return "";
        }
    }

    public static String formatErrorMessage(Language language, String err) {
        switch (language) {
            case PYTHON:
                return err.replaceAll("File .* line \\d+ *\\n", "");
            case JAVA:
                return err.replaceAll("Main\\.java:\\d+: ", "");
            default:
                return err;
        }
    }

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static String submitToJudgeZero(JZSubmissionRequestDto requestDto) throws IOException {
        requestDto.setSourceCode(Base64Utils.encodeToString(requestDto.getSourceCode().getBytes(StandardCharsets.UTF_8)));
        ObjectMapper mapper = new ObjectMapper();

        OkHttpClient client = new OkHttpClient();
        HttpUrl url = new HttpUrl.Builder()
            .scheme("https")
            .host("judge0-ce.p.rapidapi.com")
            .addPathSegment("submissions")
            .addQueryParameter("base64_encoded", "true")
            .build();

        RequestBody body = RequestBody.create(mapper.writeValueAsString(requestDto), JSON);

        Request request = new Request.Builder()
            .url(url)
            .addHeader("content-type", "application/json")
            .addHeader("x-rapidapi-host", "judge0-ce.p.rapidapi.com")
            .addHeader("x-rapidapi-key", "570c3ea12amsh7d718c55ca5d164p153fd5jsnfca4d3b2f9f9")
            .post(body)
            .build();

        Call call = client.newCall(request);
        okhttp3.Response response = call.execute();
        JZSubmissionResponse responseDto = mapper.readValue(Objects.requireNonNull(response.body()).string(),
            JZSubmissionResponse.class);

        return responseDto.getToken();
    }

    public static JzSubmissionCheckResponse retrieveFromJudgeZero(String token) throws IOException {
        // get test run info
        OkHttpClient client = new OkHttpClient();
        HttpUrl url = new HttpUrl.Builder()
            .scheme("https")
            .host("judge0-ce.p.rapidapi.com")
            .addPathSegment("submissions")
            .addPathSegment(token)
            .addQueryParameter("base64_encoded", "true")
            .build();

        Request request = new Request.Builder()
            .url(url)
            .addHeader("x-rapidapi-host", "judge0-ce.p.rapidapi.com")
            .addHeader("x-rapidapi-key", "570c3ea12amsh7d718c55ca5d164p153fd5jsnfca4d3b2f9f9")
            .get()
            .build();

        okhttp3.Response response = client.newCall(request).execute();
        ObjectMapper mapper = new ObjectMapper();
        JzSubmissionCheckResponse jzSubmissionCheckResponse = mapper.readValue(Objects.requireNonNull(response.body()).string(), JzSubmissionCheckResponse.class);
        jzSubmissionCheckResponse.setStderr(new String(Base64Utils.decodeFromString(jzSubmissionCheckResponse.getStderr())));
        jzSubmissionCheckResponse.setStdout(new String(Base64Utils.decodeFromString(jzSubmissionCheckResponse.getStdout())));
        jzSubmissionCheckResponse.setCompileOutput(new String(Base64Utils.decodeFromString(jzSubmissionCheckResponse.getCompileOutput())));
        jzSubmissionCheckResponse.setMessage(new String(Base64Utils.decodeFromString(jzSubmissionCheckResponse.getMessage())));
        return jzSubmissionCheckResponse;
    }

    public static String generateUsername() {
        String aToZ = "abcdefghjkmnopqrstuvwxyz0123456789";
        Random rand = new Random();
        StringBuilder res = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            int randIndex = rand.nextInt(aToZ.length());
            res.append(aToZ.charAt(randIndex));
        }
        return res.toString();
    }

    public static String fileToString(String fileName) {
        try {
            Resource resource = new ClassPathResource(fileName);
            InputStream inputStream = resource.getInputStream();
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            ex.printStackTrace();
            return "Error";
        }
    }

    public static void sendEmail(String key, String to, String subject, String html) {
        Configuration configuration = new Configuration()
            .domain("coderintuition.com")
            .apiKey(key)
            .from("CoderIntuition", "support@coderintuition.com");
        Response response = Mail.using(configuration)
            .to(to)
            .subject(subject)
            .html(html)
            .build()
            .send();

        if (!response.isOk()) {
            System.out.println("Error sending email to " + to + ". Response code: "
                + response.responseCode() + " with message: " + response.responseMessage());
        }
    }
}
