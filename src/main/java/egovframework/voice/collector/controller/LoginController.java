package egovframework.voice.collector.controller;

import egovframework.voice.collector.annotation.AnonymousCallable;
import egovframework.voice.collector.model.email.EmailAuth;
import egovframework.voice.collector.response.Response;
import egovframework.voice.collector.service.LoginService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Callable;
@Tag(name = "Log-in", description = "Log in API")
@ApiResponses({
@ApiResponse(responseCode = "200", description = "OK"),
@ApiResponse(responseCode = "400", description = "BAD REQUEST"),
@ApiResponse(responseCode = "404", description = "NOT FOUND"),
@ApiResponse(responseCode = "500", description = "INTERNAL SERVER ERROR !!")
})

@RequiredArgsConstructor
@RestController
@RequestMapping(produces = {MediaType.APPLICATION_JSON_VALUE})
public class LoginController {
	@Autowired LoginService service;

    @AnonymousCallable
    @RequestMapping(value = "/login", method = RequestMethod.POST)
    public Callable<Response<Object>> login(@RequestBody EmailAuth auth, HttpSession session) {
        auth.setSessionId(session.getId());
        return () -> service.doLogin(auth);
    }
}
