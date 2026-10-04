RUBY_MODULE_VALUE = 1

module ScopeModule
  class ScopeClass
    CLASS_VALUE = 2

    def ordinary(value)
      shared_name = value
      shared_name
    end

    def with_default(value = 1)
      default_local = value
      default_local
    end

    def +(other)
      operator_local = other
      operator_local
    end

    def ==(other)
      equality_local = other
      equality_local
    end

    def <=>(other)
      comparison_local = other
      comparison_local
    end

    def <<(item)
      shift_local = item
      shift_local
    end

    def []=(key, value)
      indexed_local = key || value
      indexed_local
    end

    def multiline(
      value,
      increment = 1
    )
      multiline_local = value + increment
      multiline_local
    end

    def block_owner(values)
      values.each do |value|
        do_local = value
      end
      values.each { |value| brace_local = value }
    end

    def endless(endless_value) = endless_value + 1
  end
end
