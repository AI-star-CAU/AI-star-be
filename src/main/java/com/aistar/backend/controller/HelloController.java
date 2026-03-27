package com.aistar.backend.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class HelloController {

    @GetMapping("/")
    @ResponseBody
    public String server(){
        return "AI* server";
    }

    @GetMapping("/health")
    @ResponseBody
    public String health()
    {
        return "Ai-star server is now running";
    }

    @GetMapping("/hello")
    public String hello(){
        return "hello.html";
    }




}
