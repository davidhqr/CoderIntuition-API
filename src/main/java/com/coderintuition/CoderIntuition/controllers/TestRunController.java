package com.coderintuition.CoderIntuition.controllers;

import com.coderintuition.CoderIntuition.common.Utils;
import com.coderintuition.CoderIntuition.dtos.JZSubmissionRequestDto;
import com.coderintuition.CoderIntuition.dtos.JZSubmissionResponseDto;
import com.coderintuition.CoderIntuition.dtos.JzSubmissionCheckResponseDto;
import com.coderintuition.CoderIntuition.dtos.TestRunRequestDto;
import com.coderintuition.CoderIntuition.models.Problem;
import com.coderintuition.CoderIntuition.models.Solution;
import com.coderintuition.CoderIntuition.models.TestRun;
import com.coderintuition.CoderIntuition.repositories.ProblemRepository;
import com.coderintuition.CoderIntuition.repositories.SubmissionRepository;
import com.coderintuition.CoderIntuition.repositories.TestRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.*;

@RestController
public class TestRunController {

    @Autowired
    ProblemRepository problemRepository;

    @Autowired
    TestRunRepository testRunRepository;

    @Autowired
    SubmissionRepository submissionRepository;

    private ExecutorService scheduler = Executors.newFixedThreadPool(5);

    private String wrapCode(String userCode, String solution, String language, String input) {
        if (language.equalsIgnoreCase("python")) {
            String userFunctionName = Utils.getFunctionName(userCode);
            String solFunctionName = Utils.getFunctionName(solution);
            String param = Utils.formatParam(input, language);
            List<String> codeLines = Arrays.asList(
                    userCode,
                    "",
                    "",
                    solution.replace(solFunctionName, solFunctionName + "_sol"),
                    "",
                    "",
                    "user_result = " + userFunctionName + "(" + param + ")",
                    "sol_result = " + solFunctionName + "_sol(" + param + ")",
                    "print(\"----------\")",
                    "if user_result == sol_result:",
                    "    print(\"passed|{}|{}\".format(sol_result, user_result))",
                    "else:",
                    "    print(\"failed|{}|{}\".format(sol_result, user_result))"
            );
            return String.join("\n", codeLines);
        }
        return "";
    }

    private JzSubmissionCheckResponseDto callJudgeZero(JZSubmissionRequestDto requestDto) {
        Map<String, String> header = new HashMap<>();
        header.put("content-type", "application/json");
        header.put("x-rapidapi-host", "judge0.p.rapidapi.com");
        header.put("x-rapidapi-key", "570c3ea12amsh7d718c55ca5d164p153fd5jsnfca4d3b2f9f9");

        Mono<JZSubmissionResponseDto> response = WebClient
                .create("https://judge0.p.rapidapi.com")
                .post()
                .uri("/submissions")
                .headers(httpHeaders -> httpHeaders.setAll(header))
                .body(Mono.just(requestDto), JZSubmissionRequestDto.class)
                .retrieve()
                .bodyToMono(JZSubmissionResponseDto.class);
        String token = Objects.requireNonNull(response.block()).getToken();

        final JzSubmissionCheckResponseDto[] responseData = new JzSubmissionCheckResponseDto[1];
        Future<?> future = scheduler.submit(() -> {
            try {
                while (true) {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(1));
                    Map<String, String> header1 = new HashMap<>();
                    header1.put("x-rapidapi-host", "judge0.p.rapidapi.com");
                    header1.put("x-rapidapi-key", "570c3ea12amsh7d718c55ca5d164p153fd5jsnfca4d3b2f9f9");

                    Mono<JzSubmissionCheckResponseDto> response1 = WebClient
                            .create("https://judge0.p.rapidapi.com")
                            .get()
                            .uri("/submissions/{token}", token)
                            .headers(httpHeaders -> httpHeaders.setAll(header1))
                            .retrieve()
                            .bodyToMono(JzSubmissionCheckResponseDto.class);

                    responseData[0] = Objects.requireNonNull(response1.block());
                    int statusId = responseData[0].getStatus().getId();
                    if (statusId >= 3) {
                        break;
                    }
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        });

        try {
            try {
                future.get(20, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                ex.printStackTrace();
            }
        } catch (InterruptedException | ExecutionException ex) {
            ex.printStackTrace();
        }

        return responseData[0];
    }

    @PostMapping("/testrun")
    public TestRun createTestRun(@RequestBody TestRunRequestDto testRunRequestDto) {
        Problem problem = problemRepository.findById(testRunRequestDto.getProblemId()).orElseThrow();
        Solution primarySolution = problem.getSolutions().stream().filter(Solution::getIsPrimary).findFirst().orElseThrow();
        String code = wrapCode(testRunRequestDto.getCode(), primarySolution.getCode(),
                testRunRequestDto.getLanguage(), testRunRequestDto.getInput());

        JZSubmissionRequestDto requestDto = new JZSubmissionRequestDto();
        requestDto.setSourceCode(code);
        requestDto.setLanguageId(Utils.getLanguageId(testRunRequestDto.getLanguage()));
        requestDto.setStdin("");

        JzSubmissionCheckResponseDto result = callJudgeZero(requestDto);

        TestRun testRun = new TestRun();
        testRun.setProblem(problem);
        testRun.setToken(result.getToken());
        testRun.setLanguage(testRunRequestDto.getLanguage());
        testRun.setCode(testRunRequestDto.getCode());
        testRun.setInput(testRunRequestDto.getInput());

        // error
        if (result.getStatus().getId() >= 6) {
            testRun.setStatus("error");
            testRun.setExpectedOutput("");
            testRun.setOutput("");
            String[] error = result.getStderr().split("\n");
            testRun.setStderr(error[error.length - 1]);
            testRun.setStdout("");
            // run success, check if output matches expected output
        } else if (result.getStatus().getId() == 3) {
            String[] split = result.getStdout().trim().split("----------\n");
            testRun.setStdout(split[0]);
            String[] resultOutput = split[1].split("\\|");
            testRun.setStatus(resultOutput[0]);
            testRun.setExpectedOutput(resultOutput[1]);
            testRun.setOutput(resultOutput[2]);
            testRun.setStderr("");
        }

        testRun = testRunRepository.save(testRun);
        return testRun;
    }
}
