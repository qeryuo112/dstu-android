/* NDK 29 requires explicit prototypes in ashmem_memfd.c; keep upstream bytes intact.
 * Assembly and GNUmakefile's preprocessor-only architecture probes use no libc.
 */
#if !defined(__ASSEMBLER__) && !defined(NO_LIBC_HEADER) && __STDC_HOSTED__
#include <string.h>
#endif
