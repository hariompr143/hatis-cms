import { PageResponse, request } from '@/lib/api';
import { ErrorPanel } from '@/components/ErrorPanel';
import { EmptyState } from '@/components/EmptyState';
import { StatusBadge } from '@/components/StatusBadge';
import { formatRelativeTime } from '@/lib/format';
import { accessToken } from '@/lib/session';

export const metadata = { title: 'Projects — HATIS' };

interface Project {
  id: string;
  name: string;
  slug: string;
  status: string;
  environmentCount?: number;
  createdAt: string;
  updatedAt: string;
}

export default async function ProjectsPage() {
  const token = await accessToken();
  let projects: Project[] = [];
  let error: unknown = null;

  try {
    const page = await request<PageResponse<Project>>('/v1/projects?page=0&size=100', {
      accessToken: token ?? undefined,
    });
    projects = page.items;
  } catch (cause) {
    error = cause;
  }

  return (
    <>
      <div className="page-head">
        <h1>Projects</h1>
        <p className="muted">{projects.length} in this workspace</p>
      </div>

      {error ? <ErrorPanel error={error} /> : null}

      {projects.length === 0 && !error ? (
        <EmptyState message="No projects yet. Create one to start adding content." />
      ) : null}

      {projects.length > 0 ? (
        <div className="card">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Slug</th>
                <th>Status</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {projects.map((project) => (
                <tr key={project.id}>
                  <td>{project.name}</td>
                  <td className="mono">{project.slug}</td>
                  <td>
                    <StatusBadge status={project.status} />
                  </td>
                  <td className="muted">{formatRelativeTime(project.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </>
  );
}
