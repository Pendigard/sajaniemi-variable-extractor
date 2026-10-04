GLOBAL_FIXED = 7
$global_mutated = 1
def consume(value) = value
def locals(source, read_parameter, reassigned_parameter, items_parameter)
  initialized_local = 7
  assigned_later_local = nil
  assigned_later_local = source
  reassigned_local = source
  reassigned_local = 2
  updated_local = source
  updated_local += 1
  items = []
  items << source
  items[0] = source
  items_parameter << source
  consume(read_parameter)
  reassigned_parameter = source
  consume(initialized_local)
end
def loops(values)
  before_loop = values
  values.each { |value| consume(before_loop) }
  values.each do |value|
    inside_loop = value
    consume(inside_loop)
  end
  changed_in_loop = values
  values.each { |value| changed_in_loop = value }
end
