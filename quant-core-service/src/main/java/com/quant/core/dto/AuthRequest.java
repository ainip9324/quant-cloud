package com.quant.core.dto;

import lombok.Data;

@Data
public class AuthRequest {

    private String identifier;
    private String username;
    private String email;
    private String password;
}
