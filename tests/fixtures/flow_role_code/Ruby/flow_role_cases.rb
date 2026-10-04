def flow_roles(values, condition, count)
  rising = false
  falling = true
  inert = true
  toggled = false
  reverted = false
  total = 0
  product = 1
  phased = 0
  counter = 0
  replaced = 0
  transformed_score = 0
  finalized_center = 0
  stable_only = 1
  index = 0
  while index < values.length
    value = values[index]
    rising = rising || condition
    falling = falling && condition
    inert = inert || condition
    toggled = !toggled
    reverted = true
    reverted = false
    total += value
    product *= value
    transformed_score *= 5
    transformed_score += value
    finalized_center += value
    stable_only *= 5
    phased += value
    phased = 0 if value < 0
    counter += 1
    replaced = value
    index += 1
  end
  finalized_center /= count
  [rising, falling, inert, toggled, reverted, total, product, phased, counter,
   replaced, transformed_score, finalized_center, stable_only]
end

def parameter_accumulator(accumulator, values)
  values.each { |value| accumulator += value }
  accumulator
end
