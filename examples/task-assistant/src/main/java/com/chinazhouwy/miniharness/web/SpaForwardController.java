package com.chinazhouwy.miniharness.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** SPA 深链回退：前端路由（Vue Router history 模式）刷新时转发到 index.html。 */
@Controller
public class SpaForwardController {

    private static final String[] ROUTES = {"/", "/chat", "/tasks", "/runs", "/canvas", "/evolution"};

    @GetMapping({"/", "/chat", "/tasks", "/runs", "/canvas", "/evolution"})
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
