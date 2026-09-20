cmake_minimum_required(VERSION 3.22.1)
project(forgepty C)

add_library(forgepty SHARED forgepty.c)
find_library(log-lib log)
target_link_libraries(forgepty ${log-lib})
