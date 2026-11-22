import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ErrorNotice } from './ErrorNotice';
import { SubmitButton } from './SubmitButton';
import { TextField } from './TextField';

describe('shared form components', () => {
  it('links both the hint and the error to the input', () => {
    render(
      <TextField
        label="Code"
        name="otp"
        value=""
        onChange={() => undefined}
        autoComplete="one-time-code"
        hint="Six digits."
        error="The code is 6 digits."
      />,
    );

    const input = screen.getByLabelText('Code');
    expect(input).toHaveAccessibleDescription('Six digits. The code is 6 digits.');
    expect(input).toHaveAttribute('aria-invalid', 'true');
  });

  it('marks nothing invalid and describes nothing without hint or error', () => {
    render(
      <TextField
        label="Email"
        name="email"
        value=""
        onChange={() => undefined}
        autoComplete="email"
      />,
    );

    const input = screen.getByLabelText('Email');
    expect(input).not.toHaveAttribute('aria-invalid');
    expect(input).not.toHaveAttribute('aria-describedby');
  });

  it('shows an error without a reference when there is no correlation id, and focuses it', () => {
    render(<ErrorNotice error={{ message: 'Broken.' }} />);

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Broken.');
    expect(alert).not.toHaveTextContent('Reference');
    expect(alert).toHaveFocus();
  });

  it('disables the submit button and shows the busy text while busy', () => {
    const { rerender } = render(
      <SubmitButton busy={false} busyText="Saving…">
        Save
      </SubmitButton>,
    );
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();

    rerender(
      <SubmitButton busy busyText="Saving…">
        Save
      </SubmitButton>,
    );
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled();
  });
});
