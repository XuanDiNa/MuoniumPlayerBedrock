"""Bounded damped tracking for Bedrock's tick-interpolated camera frames.

Keep the upstream speed/acceleration limits and immutable frame interpolation.
The one-tick pursuit controller reset velocity on arrival, making a slowly
moving target pulse at 20Hz. A critically damped error/velocity controller brakes
before arrival and carries continuous velocity while the target keeps moving.
"""


def apply(text):
    def replace(before, after):
        nonlocal text
        assert text.count(before) == 1, before
        text = text.replace(before, after)

    replace('''        double desired = clamp(error / Math.max(dt, 1.0e-4d), -maxSpeed, maxSpeed);
        double nextVelocity = clamp(velocity + clamp(desired - velocity, -acceleration * dt, acceleration * dt),''',
    '''        double response = 2d * acceleration / Math.max(maxSpeed, 1.0e-6d);
        double change = (response * response * error - 2d * response * velocity) * dt;
        double nextVelocity = clamp(velocity + clamp(change, -acceleration * dt, acceleration * dt),''')
    replace('''        return (target - next) * error <= 0d ? new Step(target, 0d) : new Step(next, nextVelocity);''',
    '''        return error != 0d && (target - next) * error <= 0d
                ? new Step(target, 0d) : new Step(next, nextVelocity);''')
    replace('''        double desired = clamp(error / Math.max(dt, 1.0e-4d), -maxSpeed, maxSpeed);
        double nextVelocity = clamp(velocity + clamp(desired - velocity,
                -acceleration * dt, acceleration * dt), -maxSpeed, maxSpeed);''',
    '''        double response = 2d * acceleration / Math.max(maxSpeed, 1.0e-6d);
        double change = (response * response * error - 2d * response * velocity) * dt;
        double nextVelocity = clamp(velocity + clamp(change,
                -acceleration * dt, acceleration * dt), -maxSpeed, maxSpeed);''')
    replace('''        if (Math.abs(step) >= Math.abs(error)) return new Step(current + error, 0d);''',
    '''        if (step * error > 0d && Math.abs(step) >= Math.abs(error)) return new Step(current + error, 0d);''')
    replace('''        double desiredSpeed = Math.min(maxSpeed, errorLength / Math.max(dt, 1.0e-4d));
        double desiredScale = errorLength <= 1.0e-9d ? 0d : desiredSpeed / errorLength;
        double desiredX = errorX * desiredScale;
        double desiredY = errorY * desiredScale;
        double desiredZ = errorZ * desiredScale;
        double changeX = desiredX - velocityX;
        double changeY = desiredY - velocityY;
        double changeZ = desiredZ - velocityZ;''',
    '''        double response = 2d * acceleration / Math.max(maxSpeed, 1.0e-6d);
        double changeX = (response * response * errorX - 2d * response * velocityX) * dt;
        double changeY = (response * response * errorY - 2d * response * velocityY) * dt;
        double changeZ = (response * response * errorZ - 2d * response * velocityZ) * dt;''')
    replace('''        if (stepX * errorX + stepY * errorY + stepZ * errorZ >= errorLength * errorLength) {''',
    '''        if (errorLength > 1.0e-9d
                && stepX * errorX + stepY * errorY + stepZ * errorZ >= errorLength * errorLength) {''')
    return text
