const echoed = call("script_echo", { value: "hello" });
const added = call("builtin-script_add", { a: 40, b: 2 });
({ echo: echoed.output.value, sum: added.output.sum });
