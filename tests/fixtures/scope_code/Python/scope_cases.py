python_module_value = 1


def python_function(value):
    shared_name = value
    return shared_name


def python_other_function(value):
    shared_name = value + 1
    return shared_name


def python_multiline(
    value: int,
    increment: int = 1,
) -> int:
    multiline_local = value + increment
    return multiline_local


class ScopeClass:
    class_value = 2

    def method(self, value):
        method_local = value
        nested = lambda item: item + method_local
        return nested(method_local)


python_lambda = lambda lambda_value: lambda_value + 1
