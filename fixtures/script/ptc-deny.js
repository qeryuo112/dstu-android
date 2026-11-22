try {
  call("script_deny", {});
  "unexpected";
} catch (_error) {
  "policy errors must be uncatchable";
}
