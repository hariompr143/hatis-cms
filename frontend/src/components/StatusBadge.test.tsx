import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusBadge } from './StatusBadge';

describe('StatusBadge', () => {
  it('renders a known status with its tone', () => {
    render(<StatusBadge status="PUBLISHED" />);
    const badge = screen.getByText('published');
    expect(badge).toHaveClass('positive');
  });

  it('renders an unhealthy status as negative, never as neutral', () => {
    render(<StatusBadge status="INFECTED" />);
    expect(screen.getByText('infected')).toHaveClass('negative');
  });

  it('turns underscores into spaces so enum values read as words', () => {
    render(<StatusBadge status="PENDING_VERIFICATION" />);
    expect(screen.getByText('pending verification')).toBeInTheDocument();
  });

  it('renders a placeholder rather than nothing when the status is missing', () => {
    render(<StatusBadge status={null} />);
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('falls back to neutral for a status the map does not know', () => {
    render(<StatusBadge status="SOMETHING_NEW" />);
    const badge = screen.getByText('something new');
    expect(badge).toHaveClass('neutral');
    expect(badge).not.toHaveClass('positive');
  });
});
