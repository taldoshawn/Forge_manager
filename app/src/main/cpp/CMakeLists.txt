cmake_minimum_required(VERSION 3.22.1)
project(forgepty C)
add_library(forgepty SHARED forge_pty.c)
target_compile_options(forgepty PRIVATE -Wall -Wextra)
