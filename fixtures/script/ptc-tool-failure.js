const failed = call("script_fail", {});
failed.ok ? "unexpected" : failed.error;
