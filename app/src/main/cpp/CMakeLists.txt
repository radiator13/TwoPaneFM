cmake_minimum_required(VERSION 3.22.1)
project("fileops")

add_library(fileops SHARED fileops.c)

target_compile_options(fileops PRIVATE -O2 -ffast-math -Wall -Wextra)

find_library(log-lib log)
target_link_libraries(fileops ${log-lib})
