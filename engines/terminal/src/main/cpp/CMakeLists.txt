cmake_minimum_required(VERSION 3.18.1)
project(dstu_terminal C)

add_library(pty SHARED ${OPERIT_PTY} pty_owner.c)
target_link_libraries(pty log)
target_link_options(pty PRIVATE "-Wl,-z,max-page-size=16384")
