def acquire():
    return 2

class Counter:
    def __init__(self):
        self.i = 0
        self.fixed_step = 0
        self.multi = 0
        self.reset = 0
        self.dynamic = 0
        self.mutated = 0
        self.guarded = 0
        self.unread = 0
        self.constructor_only = 0
        self.constructor_only += 1

    def next(self):
        result = self.i
        self.i += 1
        return result

    def step(self):
        print(self.fixed_step)
        self.fixed_step += 3

    def first(self):
        print(self.multi)
        self.multi += 1

    def second(self):
        print(self.multi)
        self.multi += 2

    def bad_reset(self):
        self.reset = 0
        print(self.reset)
        self.reset += 1

    def bad_dynamic(self, value):
        self.dynamic = value
        print(self.dynamic)
        self.dynamic += 1

    def bad_mutation(self):
        self.mutated.change()
        print(self.mutated)
        self.mutated += 1

    def bad_guard(self):
        print(self.guarded)
        if acquire():
            self.guarded += 1

    def no_read(self):
        self.unread += 1

    def local(self):
        score = acquire()
        score *= 100
        results = acquire()
        results = -results
        return score, results

class Left:
    def __init__(self):
        self.ambiguous = 0

class Right:
    def __init__(self):
        self.ambiguous = 0

def unknown_owner(obj):
    print(obj.ambiguous)
    obj.ambiguous += 1
