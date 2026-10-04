def observe(value)
  value
end

class MemberCopyNegatives
  def initialize
    @primitive = nil
    @external_without_transition = nil
  end

  def set_primitive(primitive)
    @primitive = primitive
  end

  def copy_for_external_read(value)
    @external_without_transition = value
  end

  def external_read
    @external_without_transition
  end
end

def direct_before(values)
  current_before = 0
  previous_before = nil
  values.each do |value|
    previous_before = current_before
    current_before = value
    observe(previous_before)
  end
  previous_before
end

def direct_after(values)
  current_after = 0
  previous_after = -1
  values.each do |value|
    current_after = value
    observe(previous_after)
    previous_after = current_after
  end
  previous_after
end

class Tracker
  def initialize
    @previous_member = nil
    @current_member = 0
    @reset_member = nil
  end

  def update(value)
    @previous_member = @current_member
    @current_member = value
    @reset_member = @current_member
  end

  def reset
    @reset_member = 0
  end

  def get_previous
    @previous_member
  end
end

def no_evolution(values)
  static_master = 1
  static_follower = nil
  values.each do |_value|
    static_follower = static_master
  end
  static_follower
end
