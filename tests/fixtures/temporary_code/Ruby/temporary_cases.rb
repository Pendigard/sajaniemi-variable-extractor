def consume(value)
  value
end

def transform(value)
  value
end

top_level_temporary = transform(1)
consume(top_level_temporary)

class TemporaryOwner
  def executable_method(value)
    method_temporary = transform(value)
    consume(method_temporary)
  end
end

def closure_case(values)
  values.each do |value|
    closure_temporary = transform(value)
    consume(closure_temporary)
  end
end
