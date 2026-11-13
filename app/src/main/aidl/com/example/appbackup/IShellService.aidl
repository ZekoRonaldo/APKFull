package com.example.appbackup;

interface IShellService {
    // Transaction id required by Shizuku for UserService teardown
    void destroy() = 16777114;

    // Returns "<exitCode>\n<combined output>"
    String exec(String cmd) = 1;
}
