package com.coderintuition.CoderIntuition.controllers;

import com.coderintuition.CoderIntuition.common.CodeTemplateFiller;
import com.coderintuition.CoderIntuition.common.Constants;
import com.coderintuition.CoderIntuition.common.Utils;
import com.coderintuition.CoderIntuition.config.AppProperties;
import com.coderintuition.CoderIntuition.enums.ProduceOutputStatus;
import com.coderintuition.CoderIntuition.models.Problem;
import com.coderintuition.CoderIntuition.models.ProduceOutput;
import com.coderintuition.CoderIntuition.pojos.request.JZSubmissionRequestDto;
import com.coderintuition.CoderIntuition.pojos.request.ProduceOutputDto;
import com.coderintuition.CoderIntuition.pojos.response.JzSubmissionCheckResponse;
import com.coderintuition.CoderIntuition.pojos.response.ProduceOutputResponse;
import com.coderintuition.CoderIntuition.pojos.response.TokenResponse;
import com.coderintuition.CoderIntuition.repositories.ProblemRepository;
import com.coderintuition.CoderIntuition.repositories.ProduceOutputRepository;
import com.coderintuition.CoderIntuition.repositories.UserRepository;
import com.coderintuition.CoderIntuition.security.CurrentUser;
import com.coderintuition.CoderIntuition.security.UserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

@RestController
@Slf4j
public class ProduceOutputController {
    @Autowired
    ProblemRepository problemRepository;

    @Autowired
    ProduceOutputRepository produceOutputRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SimpMessagingTemplate simpMessagingTemplate;

    @Autowired
    AppProperties appProperties;

    @GetMapping("/produceoutput/{token}")
    @PreAuthorize("hasRole('ROLE_MODERATOR')")
    public ProduceOutputResponse getProduceOutput(@CurrentUser UserPrincipal userPrincipal, @PathVariable String token) throws Exception {
        log.info("GET /produceoutput/{}, userId={}", token, userPrincipal.getId());
        ProduceOutput produceOutput = produceOutputRepository.findByToken(token).orElseThrow();

        if (!produceOutput.getUser().getId().equals(userPrincipal.getId())) {
            throw new Exception("Unauthorized");
        }
        return ProduceOutputResponse.fromProduceOutput(produceOutput);
    }

    @PutMapping("/produceoutput/judge0callback")
    public void produceOutputCallback(@RequestBody JzSubmissionCheckResponse data) throws IOException {
        log.info("PUT /produceoutput/judge0callback");
        log.info("data={}", data.toString());

        // get produce output info
        JzSubmissionCheckResponse result = Utils.retrieveFromJudgeZero(data.getToken(), appProperties);
        log.info("result={}", result.toString());

        // wait until produce output is written to db from createProduceOutput
        await().atMost(15, SECONDS).until(() -> produceOutputRepository.findByToken(result.getToken()).isPresent());
        log.info("Done waiting for produce output to be written to db");

        // fetch the produce output in the db
        ProduceOutput produceOutput = produceOutputRepository.findByToken(result.getToken()).orElseThrow();

        // set the results
        if (result.getStatus().getId() >= 6) { // error
            produceOutput.setStatus(ProduceOutputStatus.ERROR);
            String stderr = "";
            if (result.getCompileOutput() != null) {
                stderr = result.getCompileOutput();
            } else if (result.getStderr() != null) {
                stderr = result.getStderr();
            }
            produceOutput.setStderr(Utils.formatErrorMessage(produceOutput.getLanguage(), stderr));
            produceOutput.setStdout("");

        } else if (result.getStatus().getId() == 3) { // no compile errors
            // everything above the line is stdout, everything below is test results
            String[] split = result.getStdout().trim().split(Constants.IO_SEPARATOR);
            String[] testResult = split[1].split("\\|");

            if (testResult[0].equals("SUCCESS")) { // no errors
                // test results are formatted: {status}|{run output}
                produceOutput.setStatus(ProduceOutputStatus.SUCCESS);
                produceOutput.setOutput(testResult[1]);
                produceOutput.setStdout(split[0]);
                produceOutput.setStderr("");

            } else if (testResult[0].equals("ERROR")) { // runtime errors
                // runtime error results are formatted: {status}|{error message}
                produceOutput.setStatus(ProduceOutputStatus.ERROR);
                produceOutput.setOutput("");
                produceOutput.setStderr(Utils.formatErrorMessage(produceOutput.getLanguage(), testResult[1]));
                produceOutput.setStdout("");
            }
        }

        // save the produce output into the db
        produceOutputRepository.save(produceOutput);

        // send message to frontend
        this.simpMessagingTemplate.convertAndSend("/topic/produceoutput", result.getToken());
    }

    @PostMapping("/produceoutput")
    @PreAuthorize("hasRole('ROLE_MODERATOR')")
    public TokenResponse createProduceOutput(@CurrentUser UserPrincipal userPrincipal, @RequestBody ProduceOutputDto produceOutputDto) throws Exception {
        log.info("POST /produceoutput, userId={}, produceOutputDto={}", userPrincipal.getId(), produceOutputDto.toString());
        // retrieve the problem
        Problem problem = problemRepository.findById(produceOutputDto.getProblemId()).orElseThrow();

        // wrap the code into the produce output template
        CodeTemplateFiller filler = CodeTemplateFiller.getInstance();
        String functionName = Utils.getFunctionName(produceOutputDto.getLanguage(), problem.getCode(produceOutputDto.getLanguage()));
        String code = filler.getProduceOutputCode(produceOutputDto.getLanguage(), produceOutputDto.getCode(),
            functionName, problem.getArguments(), problem.getReturnType());
        log.info("Generated produce output code from template, code={}", code);

        // create request to JudgeZero
        JZSubmissionRequestDto jzSubmissionRequestDto = new JZSubmissionRequestDto();
        jzSubmissionRequestDto.setSourceCode(code);
        jzSubmissionRequestDto.setLanguageId(Utils.getLanguageId(produceOutputDto.getLanguage()));
        jzSubmissionRequestDto.setStdin(produceOutputDto.getInput());
        jzSubmissionRequestDto.setCallbackUrl(appProperties.getJudge0().getCallbackUrl() + "/produceoutput/judge0callback");

        // send request to JudgeZero
        String token = Utils.submitToJudgeZero(jzSubmissionRequestDto, appProperties);
        log.info("Submitted produce output to JudgeZero, token={}, jzSubmissionRequestDto={}", token, jzSubmissionRequestDto.toString());

        // save produce output to db
        ProduceOutput produceOutput = new ProduceOutput();
        produceOutput.setUser(userRepository.findById(userPrincipal.getId()).orElseThrow());
        produceOutput.setCode(produceOutputDto.getCode());
        produceOutput.setInput(produceOutputDto.getInput());
        produceOutput.setStatus(ProduceOutputStatus.RUNNING);
        produceOutput.setLanguage(produceOutputDto.getLanguage());
        produceOutput.setProblem(problem);
        produceOutput.setToken(token);
        produceOutputRepository.save(produceOutput);
        log.info("Saved produce output to database, produceOutput={}", produceOutput);

        return new TokenResponse(token);
    }
}
