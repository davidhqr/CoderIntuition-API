package com.coderintuition.CoderIntuition.dtos.request.cms;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UpdateProblemRequest {
    private Long id;
    private ProblemDto problem;
}