package com.skevenxu.humanoid.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 把 /admin 转发到静态前端入口。 */
@Controller
public class AdminPageController {

    @GetMapping({"/admin", "/admin/"})
    public String admin() {
        return "forward:/admin/index.html";
    }
}
