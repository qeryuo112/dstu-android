/* Android/Bionic build configuration for the unmodified talloc 2.4.2 source.
 * Bionic provides every C/POSIX operation used by this narrow allocation library;
 * no Samba replacement functions or host configure results are linked.
 */
#ifndef DSTU_TALLOC_REPLACE_H
#define DSTU_TALLOC_REPLACE_H
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <errno.h>
#include <limits.h>
#include <sys/types.h>
#include <unistd.h>
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2
#define HAVE_INTPTR_T 1
#define HAVE_VA_COPY 1
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#endif
