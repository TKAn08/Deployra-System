package com.paas.deployra.api;

import com.paas.deployra.deploy.RepoCheckService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repos")
public class RepoCheckController {

    private final RepoCheckService repoCheck;

    public RepoCheckController(RepoCheckService repoCheck) {
        this.repoCheck = repoCheck;
    }

    @GetMapping("/check")
    public ResponseEntity<RepoCheckService.Result> check(@RequestParam String url,
            @RequestParam(required = false) String branch) {
        RepoCheckService.Result result = repoCheck.check(url, branch);
        HttpStatus http = switch (result.status()) {
            case INVALID_URL -> HttpStatus.BAD_REQUEST;
            case UNREACHABLE -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(http).body(result);
    }
}