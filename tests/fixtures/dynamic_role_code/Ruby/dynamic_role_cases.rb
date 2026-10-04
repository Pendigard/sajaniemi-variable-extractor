def read_value
  1
end

class Box
  def initialize(value)
    @value = value
  end
end

class Profile
  def initialize(interest)
    @interest = interest
  end

  def set_interest(new_interest)
    @interest = new_interest
  end

  def get_interest
    @interest
  end
end

def dynamic_roles(collection, fixed_step, unpredictable)
  stepper = 0
  while collection.length > 0
    stepper += fixed_step
    break
  end
  dynamic_update = 0
  dynamic_update += collection[0]
  collection.each { |element| puts element }
  for numeric_index in 1..10
    puts collection[numeric_index]
  end
  current = nil
  while collection.length > 0
    current = read_value
    puts current
    break
  end
  stepper + dynamic_update
end


def projected_holders(services_response)
  services_response.each do |location|
    lat = location['location']['latitude']
    long = location['location']['longitude']
    puts lat, long
  end
end
