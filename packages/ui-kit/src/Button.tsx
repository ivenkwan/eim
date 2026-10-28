import type { ButtonHTMLAttributes } from 'react';

export type ButtonVariant = 'primary' | 'accelerated' | 'clawback' | 'locked';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  readonly variant?: ButtonVariant;
}

export function Button({ variant = 'primary', type = 'button', ...rest }: ButtonProps) {
  return <button type={type} data-variant={variant} {...rest} />;
}
